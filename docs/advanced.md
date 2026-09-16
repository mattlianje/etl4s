# Advanced


## Higher Order Nodes

```scala
import etl4s._

object CustomerOps {
  def activeOnly =
    Node[List[Customer], List[Customer]](_.filter(_.isActive))

  def topSpenders(n: Int) =
    Node[List[Customer], List[Customer]](_.sortBy(-_.spend).take(n))

  def inRegion(region: String) =
    Node[List[Customer], List[Customer]](_.filter(_.region == region))
}

import CustomerOps._

val pipeline =
     extract ~> activeOnly ~> inRegion("EU") ~> topSpenders(100) ~> load
```

## Dynamic Composition

You can build pipelines at runtime instead of writing every `~>` by hand.

```scala
import etl4s._

val rules: List[Node[Row, Row]] = List(
  trimStrings,
  dropEmpty,
  normalizeDates
)

val cleaningRules: Node[Row, Row] = rules.reduce(_ ~> _)

val pipeline =
     extract ~> cleaningRules ~> load
```


Since `reduce` throws on empty lists, you can fold from `Node.identity` (no-op Node)
and the result is a valid pipeline just with zero steps

```scala
val cleaningRules: Node[Row, Row] = rules.foldLeft(Node.identity[Row])(_ ~> _)
```


Assemble custom pipelines based on some configuration type

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


## Custom Operators

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

val pipeline =
     extract ~> transform.timed("main") ~> load
```

## Symbolic Operators

Define your own symbolic operators like `!!` and `@@` below

```scala
import etl4s._

extension [A, B](node: Node[A, B]) {
  def !!(attempts: Int): Node[A, B] = node.withRetry(attempts)
  def @@(label: String): Node[A, B] = node.tap(_ => println(label))
}

val pipeline =
     extract ~> riskyTransform !! 3 ~> load @@ "done"
```
