import cuo.*
import Task.*

@main def run =
  SingleThreadExecutor(Thread.currentThread).run {
    joining(task("a1", 2500), task("a2", 1500)).await
    joining(task("b1", 300), task("b2", 1000)).await
    task("abc", 1000).await
    println("end")
  }

def task(name: String, delay: Long): Task[Unit] = Task {
  println(">>> " + name)
  delaying(delay).await
  println("<<< " + name)
}
