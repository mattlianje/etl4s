# Testing

Use **etl4s** with whatever test framework you like.

## Run a node or pipeline

Nodes are just functions, so you can call them directly:
```scala
import etl4s._

val times5: Transform[Int, Int] = Transform(_ * 5)

times5(5)
```

You will get:
```
25
```

Run a pipeline with `unsafeRun`:

```scala
import etl4s._

val plus2:  Transform[Int, Int] = Transform(_ + 2)
val times5: Transform[Int, Int] = Transform(_ * 5)

val p: Node[Int, Int] = plus2 ~> times5

p.unsafeRun(2)
```
Gives
```
20
```
## Check for failures

To catch failures, wrap the run in a `Try`:
```scala
import scala.util.Try

Try(p.unsafeRun(2))
```
You will get:
```
Success(20)
```

Or compile to `Try`, which is handy in assertions:

```scala
import etl4s._
import scala.util.Success

val plus2:  Transform[Int, Int] = Transform(_ + 2)
val times5: Transform[Int, Int] = Transform(_ * 5)
val p: Node[Int, Int] = plus2 ~> times5

p.compile[Try].unsafeRun(2)
```
You will get:
```
Success(20)
```

See [Effect polymorphism](effect-polymorphism.md) for the other effects.

## Test pipelines that need config

Just `.provide` test config before running:

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

