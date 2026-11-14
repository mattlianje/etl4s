# Effect Polymorphism

An **etl4s** pipeline is just a description, and nothing runs while you build it.
You pick *how* it runs by compiling it to an effect with `.compile[F]`.

```scala
import etl4s._

val parse = Node[String, Int](_.trim.toInt)
val inc   = Node[Int, Int](_ + 1)

val p = 
     parse ~> inc
```

The same `p` can run in many ways.

## Run with the built-in effects

`Id`, `Try` and `Future` work out of the box.

`Id` is the default. Plain `.unsafeRun` is the same as `.compile[Id].unsafeRun`,
a normal synchronous run:

```scala
p.compile[Id].unsafeRun("41") // 42
p.unsafeRun("41") // 42
```

`Try` turns exceptions into a `Failure`:

```scala
import scala.util.{Try, Success, Failure}

p.compile[Try].unsafeRun("41") // Success(42)
parse.compile[Try].unsafeRun("oops") // Failure(NumberFormatException)
```

`Future` runs asynchronously and lets branches run concurrently:

```scala
val result: Future[Int] = pipeline.compile[Future].unsafeRun("41")
// Future(Success(42))
```

## Run branches concurrently

`&>`, `*>` and `eachPar(n)` only run in parallel when you compile to a
concurrent effect like `Future`. With plain `unsafeRun`, everything runs in order.

```scala
import etl4s._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val e1 = Node { Thread.sleep(100); 42 }
val e2 = Node { Thread.sleep(100); "Ada" }

val both = 
     e1 &> e2

both.unsafeRun(())
both.compile[Future].unsafeRun(())
```

`unsafeRun` takes ~200ms, `.compile[Future]` takes ~100ms.

See [Parallel Execution](tasks.md) for more.

## Add your own effect

To run on [Cats Effect](https://typelevel.org/cats-effect/), ZIO, Monix or any
other `F[_]`, implement `etl4s.Effect` once:

```scala
import etl4s._
import cats.effect.IO

given etl4s.Effect[IO] with {
  def pure[A](a: A): IO[A]                                    = IO.pure(a)
  def delay[A](thunk: => A): IO[A]                            = IO(thunk)
  def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B]          = fa.flatMap(f)
  def handleErrorWith[A](fa: => IO[A])(h: Throwable => IO[A]) = fa.handleErrorWith(h)

  /* Used for &>, *>, and each(Par/Slice) */
  override def both[A, B](fa: IO[A], fb: IO[B]): IO[(A, B)]   = IO.both(fa, fb)
}
```

Now the same pipeline runs on IO, and `&>` branches run on fibers:

```scala
val program: IO[Int] = p.compile[IO].unsafeRun("41")
```

The `Effect[F]` contract:

| Method | Purpose |
|--------|---------|
| `pure[A](a: A): F[A]` | Lift a value |
| `delay[A](thunk: => A): F[A]` | Suspend a side-effecting computation |
| `flatMap[A,B](fa)(f): F[B]` | Sequence steps |
| `handleErrorWith[A](fa)(h): F[A]` | Recover from failures (`onFailure`, `withRetry`) |
| `both[A,B](fa, fb): F[(A,B)]` | Combine two; override for concurrency (`&>`, `*>`, `eachPar`) |

`map` and `both` have defaults. Override `both` if you want the concurrent
operators to actually run concurrently.
