package etl4s

import scala.concurrent.ExecutionContext

/** Scala.js-specific platform implementations */
object Platform {

  /** Run two computations "in parallel" - on JS this is sequential since single-threaded */
  def runParallel[A, B](a: => A, b: => B)(implicit ec: ExecutionContext): (A, B) = {
    (a, b)
  }

  /** Sleep - on JS we can't block, so this is a no-op (immediate return) */
  def sleep(millis: Long): Unit = ()

  /** Run a sequence of computations - on JS this is sequential since single-threaded */
  def runAll[A](tasks: Seq[() => A])(implicit ec: ExecutionContext): Seq[A] = {
    tasks.map(_())
  }

  /** Create a new local variable - on JS just a simple var since single-threaded */
  def newLocalVar[T](initial: => T): LocalVar[T] = new LocalVar[T] {
    private var value: T = initial
    def get(): T         = value
    def set(v: T): Unit  = value = v
  }
}

/** Thread-local variable abstraction */
trait LocalVar[T] {
  def get(): T
  def set(v: T): Unit
}
