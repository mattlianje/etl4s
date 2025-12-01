# Advanced

## Component libraries

```scala
object CustomerOps {
  def activeOnly = Transform[List[Customer], List[Customer]](_.filter(_.isActive))
  def topSpenders(n: Int) = Transform[List[Customer], List[Customer]](_.sortBy(-_.spend).take(n))
}

import CustomerOps._
val pipeline = extract ~> activeOnly ~> topSpenders(100) ~> load
```

Pipelines read like business logic. Swap implementations for different backends - same pipeline shape, different `CustomerOps`.

## Backend-agnostic transforms

Use typeclasses for transforms that work across List, Spark Dataset, Flink DataStream, etc:

```scala
case class Sale(region: String, amount: Double)

trait Aggregatable[F[_]] {
  def groupMapReduce[A, K, V](fa: F[A])(key: A => K)(value: A => V)(reduce: (V, V) => V): Map[K, V]
}

object Aggregatables {
  implicit val list: Aggregatable[List] = new Aggregatable[List] {
    def groupMapReduce[A, K, V](fa: List[A])(key: A => K)(value: A => V)(reduce: (V, V) => V) =
      fa.groupMapReduce(key)(value)(reduce)
  }
  implicit val dataset: Aggregatable[Dataset] = new Aggregatable[Dataset] {
    def groupMapReduce[A, K, V](fa: Dataset[A])(key: A => K)(value: A => V)(reduce: (V, V) => V) =
      fa.groupByKey(key).mapValues(value).reduceGroups(reduce).collect().toMap
  }
}

def sumByRegion[F[_]](implicit F: Aggregatable[F]) =
  Transform[F[Sale], Map[String, Double]](fa => F.groupMapReduce(fa)(_.region)(_.amount)(_ + _))
```

```scala
import Aggregatables._

val extractList = Node[Unit, List[Sale]](_ => sales)
val extractSpark = Node[Unit, Dataset[Sale]](_ => spark.read...)

val localPipeline = extractList ~> sumByRegion[List] ~> load
val sparkPipeline = extractSpark ~> sumByRegion[Dataset] ~> load
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
