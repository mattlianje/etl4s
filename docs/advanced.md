# Advanced

!!! note "Scala 3 syntax"
    The `extension` blocks below use Scala 3 syntax. In Scala 2, define the same
    operators with an `implicit class`.

## Reusable Components

Group parameterized transforms into domain modules:

```scala
import etl4s._

case class Customer(isActive: Boolean, spend: Double, region: String)

object CustomerOps {

  def activeOnly =
    Transform[List[Customer], List[Customer]](_.filter(_.isActive))

  def topSpenders(n: Int) =
    Transform[List[Customer], List[Customer]](_.sortBy(-_.spend).take(n))

  def inRegion(region: String) =
    Transform[List[Customer], List[Customer]](_.filter(_.region == region))
}

import CustomerOps._
val pipeline = extract ~> activeOnly ~> inRegion("EU") ~> topSpenders(100) ~> load
```

## Dynamic Composition

A `Node` is just a value, so you can build pipelines at runtime instead of
writing every `~>` by hand. Fold a list of same-typed steps into one pipeline
with `reduce`:

```scala
import etl4s._

// Each cleaning rule is a value; the set is decided elsewhere
val rules: List[Node[Row, Row]] = List(
  trimStrings,
  dropEmpty,
  normalizeDates
)

val clean: Node[Row, Row] = rules.reduce(_ ~> _)

val pipeline = extract ~> clean ~> load
```

`reduce` throws on an empty list. When the list may be empty, fold from
`Node.identity` (the no-op step) instead. The result is a valid pipeline even
with zero steps:

```scala
val clean: Node[Row, Row] =
  rules.foldLeft(Node.identity[Row])(_ ~> _)
```

This makes it easy to assemble a pipeline from configuration: keep only the
steps that are switched on, then fold:

```scala
case class Config(dedupe: Boolean, enrich: Boolean)

def buildPipeline(cfg: Config): Node[Row, Row] = {
  val optional = List(
    cfg.dedupe -> dedupe,
    cfg.enrich -> enrich
  )
  optional
    .collect { case (true, step) => step }
    .foldLeft(Node.identity[Row])(_ ~> _)
}
```

The folded pipeline is a normal `Node`. It still runs with `unsafeRun` /
`compile[F]` and stays inspectable via `.stages`, `.toMermaid`, and `.toDot`.

## Custom Operators

Add domain-specific operators via extension methods (Scala 3):

```scala
import etl4s._

extension [A, B](node: Node[A, B]) {
  def timed(label: String): Node[A, B] = Node { input =>
    val start = System.currentTimeMillis()
    val result = node(input)
    println(s"$label: ${System.currentTimeMillis() - start}ms")
    result
  }
}

val pipeline = extract ~> transform.timed("main") ~> load
```

## Symbolic Operators

Define your own (Scala 3):

```scala
import etl4s._

extension [A, B](node: Node[A, B]) {
  def !!(attempts: Int): Node[A, B] = node.withRetry(attempts)
  def @@(label: String): Node[A, B] = node.tap(_ => println(label))
}

val pipeline = extract ~> riskyTransform !! 3 ~> load @@ "done"
```
