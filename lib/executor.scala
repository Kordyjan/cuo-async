package cuo

import scala.continuations
import scala.runtime.LazyUnit
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ConcurrentSkipListSet
import java.time.LocalDateTime
import scala.util.*
import scala.annotation.tailrec

trait Waker:
  def wakeNow: Task[?] => Unit

trait TimedWaker extends Waker:
  override def wakeNow: Task[?] => Unit = task => wake(task, System.currentTimeMillis)
  def wake(task: Task[?], timestamp: Long): Unit


extension (waker: TimedWaker) def sub(substitute: Task[?]) = new TimedWaker:
  def wake(task: Task[?], timestamp: Long) =
    waker.wake(substitute, timestamp)

trait AsyncContext extends continuations.Executor[Any]:
  type Extract = Any
  type Suspended[A] = Task[A]

object ProxyContext extends cuo.AsyncContext:
  type Output[A] = Task[A]

  def process[Res](sm: Coroutine[Res]) = new Task[Res]:
    private var inner: Task[?] = null
    private var continuation: sm.Frame = null

    def poll(waker: TimedWaker): Poll[Res] =
      import sm.State.*

      def handle: sm.State => Poll[Res] =
        case Finished(res) => Poll.Ready(res.asInstanceOf[Res])
            case Failed(e) => Poll.Failure(e)
            case Progressed(task, frame) =>
              inner = task
              continuation = frame
              poll(waker)

      if inner == null then
        handle(sm.start())
      else
        inner.poll(waker.sub(this)) match
          case Poll.Pending => Poll.Pending
          case Poll.Failure(e) => Poll.Failure(e)
          case Poll.Ready(value) => handle(continuation.resume(value))
end ProxyContext

class SingleThreadExecutor(private val thread: Thread) extends AsyncContext:
  type Output[A] = Try[A]

  def process[Res](sm: Coroutine[Res]): Try[Res] =
    import sm.State.*

    case class TimedTask(val timestamp: Long, val task: Task[?], val continuation: sm.Frame) extends Comparable[TimedTask]:
      def compareTo(other: TimedTask) = timestamp.compareTo(other.timestamp)

    val queue = new ConcurrentSkipListSet[TimedTask]

    class CWaker(continuation: sm.Frame) extends TimedWaker:
      def wake(task: Task[_], timestamp: Long) =
        queue.add(TimedTask(timestamp, task, continuation))
        if Thread.currentThread != thread then thread.interrupt

    def wait(millis: Long) = try Thread.sleep(millis) catch
      case e: InterruptedException => ()

    @tailrec def loop(): Try[Any] =
      if queue.isEmpty then
        println("iddling")
        wait(300)
        loop()
      else
        val delta = queue.first.timestamp - System.currentTimeMillis
        if delta > 0 then wait(delta)
        val TimedTask(timestamp, task, continuation) = queue.pollFirst
        task.poll(CWaker(continuation)) match
          case Poll.Ready(extract) => handle(continuation.resume(extract))
          case Poll.Failure(error) => Failure(error)
          case Poll.Pending => loop()


    def handle: sm.State => Try[Any] =
      case Finished(answer) => Success(answer)
      case Failed(error) => Failure(error)
      case Progressed(task, frame) =>
        task.poll(CWaker(frame)) match
          case Poll.Ready(extract) => handle(frame.resume(extract))
          case Poll.Failure(error) => Failure(error)
          case Poll.Pending => loop()

    handle(sm.start()).asInstanceOf[Try[Res]]
  end process
