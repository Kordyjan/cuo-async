package cuo

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.chaining.*
import java.lang.Character.Subset
import scala.collection.generic.Subtractable

enum Poll[+A]:
  case Failure(error: Throwable)
  case Ready(value: A)
  case Pending

trait Task[+A]:
  def poll(waker: TimedWaker): Poll[A]

object Task:
  var mem = 0

  def delaying(millis: Long) = new Task[Unit]:
    if (millis == 2500) then
      mem += 1
      if mem == 2 then Thread.dumpStack
    var timestamp: Long = 0
    def poll(waker: TimedWaker): Poll[Unit] =
      if timestamp == 0 then
        timestamp = System.currentTimeMillis + millis
        waker.wake(this, timestamp)
        Poll.Pending
      else if timestamp <= System.currentTimeMillis then
        Poll.Ready(())
      else
        waker.wake(this, timestamp)
        Poll.Pending

  def joining[A, B](a: Task[A], b: Task[B]): Task[(A, B)] = Joining((a, b))

  def just[A](value: => A) = new Task[A]:
    def poll(waker: TimedWaker): Poll[A] = try { Poll.Ready(value) } catch
      case e: Throwable => Poll.Failure(e)

  transparent inline def apply[T](inline block: Async[T]) = ProxyContext.run(block)

  private abstract class Subtask[T, R](private var delegate: Task[T]) extends Task[R]:
    var result: Option[T] = None

    def recombine(answer: Poll[T]): Poll[R]

    def poll(waker: TimedWaker) =
      val res = delegate.poll(waker.sub(this))
      res match
        case Poll.Ready(value) => result = Some(value)
        case _ => ()
      recombine(res)
  end Subtask

  class Joining[T <: Tuple](tasks: Tuple.Map[T, Task]) extends Task[T]:
    private val finished = AtomicBoolean(false)
    private val subtasks = tasks.toList.asInstanceOf[List[Task[?]]].map(JoinSubtask(_))

    private class JoinSubtask(delegate: Task[?]) extends Subtask[Any, T](delegate):
      def recombine(answer: Poll[Any]): Poll[T] = answer match
        case Poll.Pending => Poll.Pending
        case Poll.Failure(e) => Poll.Failure(e)
        case Poll.Ready(_) =>
          val result = subtasks.map(_.result).foldRight[Option[List[Any]]](Some(Nil)) {
            case (Some(partial), Some(res)) => Some(partial :: res)
            case (_, _) => None
          }
          result match
            case Some(res) if !finished.getAndSet(true) => Poll.Ready(Tuple.fromArray(res.toArray).asInstanceOf[T])
            case _ => Poll.Pending

    def poll(waker: TimedWaker): Poll[T] =
      subtasks.map(t => t.poll(waker.sub(t)))
        .find(p => p.isInstanceOf[Poll.Failure[?]] || p.isInstanceOf[Poll.Ready[?]])
        .getOrElse(Poll.Pending)
  end Joining

end Task

type Async[A] = AsyncContext#C ?=> A

extension [Env, A](task: Task[A]) inline def await: Async[A] = summon[AsyncContext#C].suspend(task)