# Advanced


## Pipelines are values

Since pipelines are values, you can do things with them before they ever run. Given:

```scala
val billing = parse ~> applyTax ~> format
```

**Unit test pipeline shape**

```scala
test("etl graph is wired as designed") {
  assertEquals(billing.stages.map(_.name), List("parse", "applyTax", "format"))
  assertEquals(billing.stages.map(s => s.in -> s.out).last, "Double" -> "String")
}
```

**Govern dataflow architecture**

```scala
def audit(p: Node[?, ?]): Unit = {
  val forbidden = p.stages.filter(_.fullName.startsWith("com.acme.legacy"))
  require(forbidden.isEmpty, s"pipeline pulls in banned stages: ${forbidden.map(_.name)}")
}
```

**Generate docs that never drift**

```scala
os.write.over(os.pwd / "docs" / "billing.mmd", billing.toMermaid)
```


## Write reusable node factories

Functions that return nodes are a simple way to build a library of reusable steps:

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

## Build pipelines at runtime

You don't have to write every `~>` by hand. Build the pipeline from a list:

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


`reduce` throws on an empty list, so fold from `Node.identity` (a node that does nothing) instead:

```scala
val cleaningRules: Node[Row, Row] = rules.foldLeft(Node.identity[Row])(_ ~> _)
```


Or pick the steps from config:

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


## Add your own operators

Extension methods are all you need:

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

## Add symbolic operators

Symbols work too, like `!!` and `@@` here:

```scala
import etl4s._

extension [A, B](node: Node[A, B]) {
  def !!(attempts: Int): Node[A, B] = node.withRetry(attempts)
  def @@(label: String): Node[A, B] = node.tap(_ => println(label))
}

val pipeline =
     extract ~> riskyTransform !! 3 ~> load @@ "done"
```
