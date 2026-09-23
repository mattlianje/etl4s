# Core Concepts

**etl4s** has one core building block:
```scala
Node[-In, +Out]
```
A Node wraps a lazily-evaluated function `In => Out`. Chain them with `~>` to build pipelines.

## Node types
To improve readability and express intent, **etl4s** defines three aliases: `Extract`, `Transform` and `Load`. All behave the same under the hood.

```scala
type Extract[In, Out]   = Node[In, Out]
type Transform[In, Out] = Node[In, Out]
type Load[In, Out]      = Node[In, Out]
```

## Building pipelines
```scala
import etl4s._

val readCsv    = Node("alice\nbob\ncarol")
val countUsers = Node[String, Int](csv => csv.split("\n").length)
val report     = Node[Int, Unit](count => println(s"Processed $count users"))

val pipeline = readCsv ~> countUsers ~> report

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

## Pipelines are values
Building a pipeline runs nothing. Every combinator (`~>`, `&`, `>>`, ...) just grows an
immutable AST - a free profunctor over your plain functions. `a ~> b ~> c` is literally
a tree of case classes:

```scala
AndThen(
  AndThen(Step("a", ...), Step("b", ...)),
  Step("c", ...)
)
```

<p align="center">
  <img src="https://raw.githubusercontent.com/mattlianje/etl4s/master/pix/pipeline-tree.svg" width="210">
</p>

Because a pipeline is just this tree, you can interpret it however you like. That is what
makes etl4s effect polymorphic: `.compile[F]` folds the same tree into `In => F[Out]` for
any effect `F` (`Try`, `Future`, cats-effect `IO`, ZIO, Kyo ...). See
[Effect polymorphism](effect-polymorphism.md).

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
