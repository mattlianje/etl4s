package etl4s

import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration.Duration

/** JVM-specific platform implementations */
object Platform {

  /** Run two computations in parallel and wait for both results */
  def runParallel[A, B](a: => A, b: => B)(implicit ec: ExecutionContext): (A, B) = {
    val fa = Future(a)
    val fb = Future(b)
    Await.result(fa.zip(fb), Duration.Inf)
  }

  /** Sleep the current thread for the specified milliseconds */
  def sleep(millis: Long): Unit = Thread.sleep(millis)

  /** Run a sequence of computations in parallel and collect all results */
  def runAll[A](tasks: Seq[() => A])(implicit ec: ExecutionContext): Seq[A] = {
    val futures = tasks.map(task => Future(task()))
    Await.result(Future.sequence(futures), Duration.Inf)
  }

  /** Create a new thread-local variable */
  def newLocalVar[T](initial: => T): LocalVar[T] = new LocalVar[T] {
    private val tl = new ThreadLocal[T] {
      override def initialValue(): T = initial
    }
    def get(): T        = tl.get()
    def set(v: T): Unit = tl.set(v)
  }
}

/** Thread-local variable abstraction */
trait LocalVar[T] {
  def get(): T
  def set(v: T): Unit
}
