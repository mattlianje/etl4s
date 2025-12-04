# Advanced

## Reusable Components

Build parameterized transforms that read like business logic:

```scala
object CustomerOps {
  def activeOnly = Transform[List[Customer], List[Customer]](_.filter(_.isActive))
  def topSpenders(n: Int) = Transform[List[Customer], List[Customer]](_.sortBy(-_.spend).take(n))
  def inRegion(region: String) = Transform[List[Customer], List[Customer]](_.filter(_.region == region))
}

import CustomerOps._
val pipeline = extract ~> activeOnly ~> inRegion("EU") ~> topSpenders(100) ~> load
```

Group related transforms into domain modules:

```scala
object OrderOps {
  def recent(days: Int) = Transform[List[Order], List[Order]](
    _.filter(_.date.isAfter(LocalDate.now.minusDays(days)))
  )
  def highValue(threshold: Double) = Transform[List[Order], List[Order]](
    _.filter(_.total > threshold)
  )
  def byStatus(status: Status) = Transform[List[Order], List[Order]](
    _.filter(_.status == status)
  )
}

// Compose freely
val urgentOrders = extract ~> OrderOps.recent(7) ~> OrderOps.highValue(1000) ~> notify
```

## Dynamic Pipeline Assembly

Build pipelines based on runtime configuration:

```scala
case class JobConfig(
  filterInactive: Boolean,
  topN: Option[Int],
  regions: List[String]
)

def buildPipeline(config: JobConfig): Node[List[Customer], List[Customer]] = {
  var pipeline: Node[List[Customer], List[Customer]] = Transform(identity)

  if (config.filterInactive)
    pipeline = pipeline ~> CustomerOps.activeOnly

  config.regions match {
    case Nil      => // no filter
    case r :: Nil => pipeline = pipeline ~> CustomerOps.inRegion(r)
    case rs       => pipeline = pipeline ~> Transform(_.filter(c => rs.contains(c.region)))
  }

  config.topN.foreach { n =>
    pipeline = pipeline ~> CustomerOps.topSpenders(n)
  }

  pipeline
}

// Usage
val config = JobConfig(filterInactive = true, topN = Some(50), regions = List("US", "EU"))
val pipeline = extract ~> buildPipeline(config) ~> load
```

Or use fold for a more functional style:

```scala
def buildPipeline(steps: List[Node[List[Customer], List[Customer]]]): Node[List[Customer], List[Customer]] =
  steps.foldLeft(Transform[List[Customer], List[Customer]](identity))(_ ~> _)

val steps = List(
  CustomerOps.activeOnly,
  CustomerOps.inRegion("US"),
  CustomerOps.topSpenders(100)
)

val pipeline = extract ~> buildPipeline(steps) ~> load
```

## Custom operators

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

## Symbolic operators

Define your own symbolic operators:

```scala
extension [A, B](node: Node[A, B]) {
  // Retry operator
  def !!(attempts: Int): Node[A, B] = node.withRetry(attempts)

  // Log-through operator
  def @@(label: String): Node[A, B] = node.tap(_ => Trace.log(label))
}

val pipeline = extract ~> riskyTransform !! 3 ~> load @@ "done"
```
