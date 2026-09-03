# Effect Polymorphism

An **etl4s** pipeline is just a description: a small AST of arrow/profunctor
combinators. Nothing runs while you stitch it together. You choose *how* it runs
by compiling it to an effect `F[_]` with `.compile[F]`.

```scala
import etl4s._

val parse = Transform[String, Int](_.trim.toInt)
val inc   = Transform[Int, Int](_ + 1)

val pipeline = parse ~> inc
```

The same `pipeline` value can be interpreted many ways.

## Built-in effects

**etl4s** ships three effects out of the box: `Id`, `Try`, and `Future`.

`Id` is the default - `.unsafeRun` (with no `.compile`) is exactly
`.compile[Id].unsafeRun`, a plain synchronous run:

```scala
pipeline.compile[Id].unsafeRun("41")  // 42
pipeline.unsafeRun("41")              // 42
```

`Try` catches thrown exceptions into `Success`/`Failure`:

```scala
import scala.util.{Try, Success, Failure}

pipeline.compile[Try].unsafeRun("41")   // Success(42)
parse.compile[Try].unsafeRun("oops")    // Failure(NumberFormatException)
```

`Future` runs asynchronously and enables concurrent branches:

```scala
val result: Future[Int] = pipeline.compile[Future].unsafeRun("41")
// Future(Success(42))
```

## Where concurrency comes from

The concurrent operators (`&>`, `*>`, and `eachPar(n)`) only run in parallel
when you compile to an effect whose `Effect[F]` implements `both` concurrently
(like `Future`). Under the default `Id` interpreter everything is sequential.

```scala
import etl4s._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val e1 = Extract { Thread.sleep(100); 42 }
val e2 = Extract { Thread.sleep(100); "Ada" }

val both = e1 &> e2

both.unsafeRun(())
both.compile[Future].unsafeRun(())
```

`unsafeRun` runs sequentially (~200ms) under `Id`; `.compile[Future]` runs the
two branches concurrently (~100ms).

See [Parallel Execution](tasks.md) for more.

## Add your own effect

Want to run on the [Cats Effect](https://typelevel.org/cats-effect/) fiber
runtime, ZIO, Monix, or any `F[_]`? Implement `etl4s.Effect` once:

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

Now the same pipeline runs on `IO`, and `&>` branches on CE fibers:

```scala
val program: IO[Int] = pipeline.compile[IO].unsafeRun("41")
```

The `Effect[F]` contract:

| Method | Purpose |
|--------|---------|
| `pure[A](a: A): F[A]` | Lift a value |
| `delay[A](thunk: => A): F[A]` | Suspend a side-effecting computation |
| `flatMap[A,B](fa)(f): F[B]` | Sequence steps |
| `handleErrorWith[A](fa)(h): F[A]` | Recover from failures (`onFailure`, `withRetry`) |
| `both[A,B](fa, fb): F[(A,B)]` | Combine two; override for concurrency (`&>`, `*>`, `eachPar`) |

`map` and `both` have sensible defaults; override `both` to make the concurrent
operators actually concurrent.
