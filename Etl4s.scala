import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Try, Success, Failure}

case class Reader[R, A](run: R => A) {
  def map[B](f: A => B): Reader[R, B] =
    Reader(r => f(run(r)))

  def flatMap[B](f: A => Reader[R, B]): Reader[R, B] =
    Reader(r => f(run(r)).run(r))
}

object Reader {
  def ask[R]: Reader[R, R] = Reader(identity)
}

case class RetryConfig(
    maxAttempts: Int = 3,
    initialDelay: Duration = 100.millis,
    backoffFactor: Double = 2.0
)

sealed trait Node[-A, +B] {
  def runSync: A => B
  def runAsync(implicit ec: ExecutionContext): A => Future[B]
  def run[C]: C => A => B = _ => runSync

  def withRetry(config: RetryConfig): Node[A, B] = new Node[A, B] {
    def runSync: A => B = { input =>
      def attempt(remaining: Int, delay: Duration): Try[B] = {
        Try(Node.this.runSync(input)) match {
          case Success(result) => Success(result)
          case Failure(e) if remaining > 1 =>
            Thread.sleep(delay.toMillis)
            attempt(
              remaining - 1,
              Duration.fromNanos((delay.toNanos * config.backoffFactor).toLong)
            )
          case Failure(e) => Failure(e)
        }
      }
      attempt(
        config.maxAttempts,
        config.initialDelay
      ).get
    }

    def runAsync(implicit ec: ExecutionContext): A => Future[B] = { input =>
      def attempt(remaining: Int, delay: Duration): Future[B] = {
        Future(Try(Node.this.runSync(input))).flatMap {
          case Success(result) => Future.successful(result)
          case Failure(e) if remaining > 1 =>
            Future {
              Thread.sleep(delay.toMillis)
            }.flatMap { _ =>
              attempt(
                remaining - 1,
                Duration.fromNanos(
                  (delay.toNanos * config.backoffFactor).toLong
                )
              )
            }
          case Failure(e) => Future.failed(e)
        }
      }
      attempt(config.maxAttempts, config.initialDelay)
    }
  }
}

case class Extract[I, O](f: I => O) extends Node[I, O] {
  def runSync: I => O = f
  def runAsync(implicit ec: ExecutionContext): I => Future[O] =
    i => Future(f(i))
}

object Extract {
  def apply[A](value: A): Extract[Unit, A] = Extract(_ => value)

  def async[I, O](f: I => Future[O])(implicit
      ec: ExecutionContext
  ): Extract[I, O] =
    Extract(i => Await.result(f(i), Duration.Inf))
}

case class Transform[A, B](f: A => B) extends Node[A, B] {
  def runSync: A => B = f
  def runAsync(implicit ec: ExecutionContext): A => Future[B] =
    a => Future(f(a))

  def andThen[C](that: Transform[B, C]): Transform[A, C] =
    Transform(f andThen that.f)

}

case class Load[I, O](f: I => O) extends Node[I, O] {
  def runSync: I => O = f
  def runAsync(implicit ec: ExecutionContext): I => Future[O] =
    i => Future(f(i))
}

object Load {
  def async[I, O](f: I => Future[O])(implicit
      ec: ExecutionContext
  ): Load[I, O] =
    Load(i => Await.result(f(i), Duration.Inf))
}

object Etl4s {
  case class Pipeline[A, B](node: Node[A, B]) {
    def ~>[C](next: Node[B, C]): Pipeline[A, C] = {
      Pipeline(new Node[A, C] {
        def runSync: A => C = node.runSync andThen next.runSync
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          node.runAsync.apply(a).flatMap(next.runAsync.apply)
        }
      })
    }

    def flatMap[C](f: B => Pipeline[A, C]): Pipeline[A, C] = {
      Pipeline(new Node[A, C] {
        def runSync: A => C = { a =>
          val b = node.runSync(a)
          f(b).runSync(a)
        }
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          node.runAsync.apply(a).flatMap(b => f(b).runAsync(a))
        }
      })
    }

    def flatten[C](implicit ev: B <:< Pipeline[A, C]): Pipeline[A, C] = {
      Pipeline(new Node[A, C] {
        def runSync: A => C = { a =>
          val innerPipeline = ev(node.runSync(a))
          innerPipeline.runSync(a)
        }
        def runAsync(implicit ec: ExecutionContext): A => Future[C] = { a =>
          node.runAsync.apply(a).flatMap(b => ev(b).runAsync(a))
        }
      })
    }
    def map[C](f: B => C): Pipeline[A, C] = this ~> Transform(f)

    def runSync(input: A): B = node.runSync(input)
    def runAsync(input: A)(implicit ec: ExecutionContext): Future[B] =
      node.runAsync.apply(input)
    def unsafeRun(input: A): B = runSync(input)
    def runSyncSafe(input: A): Try[B] = Try(runSync(input))
    def withRetry(config: RetryConfig = RetryConfig()): Pipeline[A, B] =
      Pipeline(node.withRetry(config))
  }

  implicit class NodeOps[A, B](node: Node[A, B]) {
    def ~>[C](next: Node[B, C]): Pipeline[A, C] =
      Pipeline(node) ~> next
  }

  implicit class ExtractOps[I, O1](e1: Extract[I, O1]) {
    def &[O2](e2: Extract[I, O2]): Extract[I, (O1, O2)] = Extract { input =>
      (e1.runSync(input), e2.runSync(input))
    }

    def &>[O2](e2: Extract[I, O2])(implicit
        ec: ExecutionContext
    ): Extract[I, (O1, O2)] = Extract { input =>
      val f1 = e1.runAsync.apply(input.asInstanceOf[I])
      val f2 = e2.runAsync.apply(input.asInstanceOf[I])
      Await.result(
        for {
          r1 <- f1
          r2 <- f2
        } yield (r1, r2),
        Duration.Inf
      )
    }

    def merged[Out](implicit
        flattener: Flatten.Aux[O1, Out]
    ): Extract[I, Out] = Extract[I, Out] { i =>
      flattener(e1.runSync(i))
    }
  }

  implicit class LoadOps[I, O1](l1: Load[I, O1]) {
    def &[O2](l2: Load[I, O2]): Load[I, (O1, O2)] = Load { input =>
      (l1.runSync(input), l2.runSync(input))
    }

    def &>[O2](l2: Load[I, O2])(implicit
        ec: ExecutionContext
    ): Load[I, (O1, O2)] =
      Load { input =>
        val f1 = l1.runAsync.apply(input.asInstanceOf[I])
        val f2 = l2.runAsync.apply(input.asInstanceOf[I])
        Await.result(
          for {
            r1 <- f1
            r2 <- f2
          } yield (r1, r2),
          Duration.Inf
        )
      }
  }

  /* Yuck - but don't want to use shapeless */
  trait Flatten[A] {
    type Out
    def apply(a: A): Out
  }

  trait P0 {
    implicit def base[A]: Flatten.Aux[A, A] = new Flatten[A] {
      type Out = A
      def apply(a: A): A = a
    }
  }

  trait P1 extends P0 {
    implicit def tuple3[A, B, C]: Flatten.Aux[((A, B), C), (A, B, C)] =
      new Flatten[((A, B), C)] {
        type Out = (A, B, C)
        def apply(t: ((A, B), C)): (A, B, C) = {
          val ((a, b), c) = t
          (a, b, c)
        }
      }
  }

  object Flatten extends P1 {
    type Aux[A, B] = Flatten[A] { type Out = B }
    implicit def tuple4[A, B, C, D]: Aux[(((A, B), C), D), (A, B, C, D)] =
      new Flatten[(((A, B), C), D)] {
        type Out = (A, B, C, D)
        def apply(t: (((A, B), C), D)): (A, B, C, D) = {
          val (((a, b), c), d) = t
          (a, b, c, d)
        }
      }
  }
}
