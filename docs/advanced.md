# Advanced

## Reusable Components

Group parameterized transforms into domain modules:

```scala
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

## Custom Operators

Add domain-specific operators via extension methods:

```scala
extension [A, B](node: Node[A, B]) {
  def timed(label: String): Node[A, B] = Node { input =>
    val start = System.currentTimeMillis()
    val result = node(input)
    Trace.log(s"$label: ${System.currentTimeMillis() - start}ms")
    result
  }
}

val pipeline = extract ~> transform.timed("main") ~> load
```

## Symbolic Operators

Define your own:

```scala
extension [A, B](node: Node[A, B]) {
  def !!(attempts: Int): Node[A, B] = node.withRetry(attempts)
  def @@(label: String): Node[A, B] = node.tap(_ => Trace.log(label))
}

val pipeline = extract ~> riskyTransform !! 3 ~> load @@ "done"
```
