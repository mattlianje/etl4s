# Testing

Use **etl4s** with the testing framework of your choice

Run nodes like normal functions
```scala
import etl4s._

val times5: Transform[Int, Int] = Transform(_ * 5)

times5(5)
```

You will get:
```
25
```

Run pipelines with `unsafeRun`:

```scala
import etl4s._

val plus2:  Transform[Int, Int] = Transform(_ + 2)
val times5: Transform[Int, Int] = Transform(_ * 5)

val p: Pipeline[Int, Int] = plus2 ~> times5

p.unsafeRun(2)
```
Gives
```
20
```
If you want to capture failures, wrap the run in your own `Try`:
```scala
import scala.util.Try

Try(p.unsafeRun(2))
```
You will get:
```
Success(20)
```

Or run through an effect with `.compile[F]` - `.compile[Try]` folds the outcome
into a `Try` for you, which is convenient in assertions:

```scala
import etl4s._
import scala.util.Success

val plus2:  Transform[Int, Int] = Transform(_ + 2)
val times5: Transform[Int, Int] = Transform(_ * 5)
val p: Pipeline[Int, Int] = plus2 ~> times5

p.compile[Try].unsafeRun(2)
```
You will get:
```
Success(20)
```

See [Effect polymorphism](effect-polymorphism.md) for the other effects.

## Testing config pipelines

For nodes that `.requires` config, supply it with `.provide(cfg)` (or its alias
`.provideContext`) before running - swap in test config at the edge:

```scala
import etl4s._

case class Cfg(multiplier: Int)

val scaled = Transform[Int, Int].requires[Cfg] { cfg => n => n * cfg.multiplier }

scaled.provide(Cfg(10)).unsafeRun(5)
```
You will get:
```
50
```

## Testing with Traces

For testing with execution insights, see the [Tracing](trace.md) section. You can test the result and timing of a run:

```scala
import etl4s._

val pipeline = Transform[String, Int](_.length)
val trace = pipeline.unsafeRunTrace("test")

assert(trace.result == 4)
assert(trace.timeElapsedMillis >= 0)
```

