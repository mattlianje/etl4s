# Core Concepts

**etl4s** has one core building block:
```scala
Node[-In, +Out]
```
A Node wraps a lazily-evaluated function `In => Out`. Chain them with `~>` to build pipelines.

## Node types
To improve readability and express intent, **etl4s** defines three aliases: `Extract`, `Transform` and `Load`. All behave the same under the hood.

```scala
type Extract[-In, +Out]   = Node[In, Out]
type Transform[-In, +Out] = Node[In, Out]
type Load[-In, +Out]      = Node[In, Out]
```

## Building pipelines
```scala
import etl4s._

val readCsv    = Node("alice\nbob\ncarol")
val countUsers = Node[String, Int](csv => csv.split("\n").length)
val report     = Node[Int, Unit](count => println(s"Processed $count users"))

val pipeline = 
     readCsv ~> countUsers ~> report

pipeline.unsafeRun()
```
Prints:
```
Processed 3 users
```

The idiomatic effectful run is `.compile[F].unsafeRun(...)` (e.g. `Try`,
`Future`, or a cats-effect `IO`) - see [Effect polymorphism](effect-polymorphism.md).

Create standalone nodes:
You can run standalone nodes like simple functions:
```scala
val toUpper = Node[String, String](_.toUpperCase)

toUpper("hello")
```
You will get:
```
HELLO
```

## Running pipelines
Call like a function:
```scala
pipeline(())
```

Or be explicit:
```scala
pipeline.unsafeRun()
```


!!! note
    **etl4s** also has a `Reader` type for dependency injection. Use `.requires` to turn any Node into a `Reader[Config, Node]`. The `~>` operator works between Nodes and Readers. See [Configuration](config.md) for details.
