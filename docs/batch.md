---
api:
  - sig: "each(sub)"
  - sig: "eachPar(n)(sub)"
  - sig: "eachSlice(size)(sub)"
  - sig: "collectEach(sub)"
  - sig: "collectEachPar(n)(sub)"
  - sig: "filterEach(pred)"
  - sig: "filterEachPar(n)(pred)"
  - sig: "trait Batchable[C, A, F]"
---

# Batch Collections

Sub-pipelines often need to run over every element of a list-like source. This is why etl4s
has a family of `…Each` combinators. They work on `List`, `Vector`, `Seq`,
`Set`, and `Iterable` out of the box (plus `LazyList` on Scala 3), and any
[custom container](#custom-batchables) you teach it about.

| Combinator | What it does |
|------------|--------------|
| `each(sub)` | run `sub` on every element |
| `eachSlice(size)(sub)` | run `sub` on chunks of `size` elements |
| `collectEach(sub)` | run `sub: A => Option[B]`, keep the `Some`s |
| `filterEach(pred)` | keep the elements where `pred` holds |

Each has a `…Par(n)` variant (`eachPar`, `collectEachPar`, `filterEachPar`) that runs up to
`n` elements concurrently under a [concurrent effect](effect-polymorphism.md), sequentially
under the default `Id` interpreter.

```scala
import etl4s._

val clean  = Node[Int, Int](_ + 1)
val enrich = Node[Int, String](n => s"v$n")
val extractNumbers  = Node(_ => List(1, 2, 3))
```

## `each`: one element at a time

Apply the inner sub-pipeline to each element, sequentially.

```scala
val pipeline =
     extractNumbers ~> each(clean ~> enrich)

pipeline.unsafeRun()
```
You will get:
```
List("v2", "v3", "v4")
```

The concrete collection type is preserved through the fold.

## `eachPar(n)`: up to `n` in flight

Same as `each`, but processes up to `n` elements concurrently.

```scala
val pipeline =
     extractNumbers ~> eachPar(8)(clean ~> enrich)
```

!!! note "Concurrency needs a concurrent effect"

    Like `&>` and `*>`, `eachPar` only runs in parallel when you
    [compile to a concurrent effect](effect-polymorphism.md). Under the default
    `Id` interpreter (`.unsafeRun`) it runs sequentially.

## `eachSlice(size)`: whole chunks at a time

Feed the sub-pipeline chunks of `size` elements instead of single elements.
Ideal for bulk upserts or batched API calls.

```scala
val bulkUpsert =
     Node[List[Int], Unit](chunk => println(s"upserting ${chunk.size} rows"))

val pipeline = 
     extractNumbers ~> eachSlice(500)(bulkUpsert)
```

## `collectEach` / `collectEachPar`: map and drop

When the inner step returns an `Option`, `collectEach` keeps the `Some` values
and drops the `None`s, a batch-flavoured `collect`.

```scala
val parse = Node[String, Option[Int]](s => scala.util.Try(s.toInt).toOption)
val extractBadNumbers = Node(_ => List("1", "2", "oops", "4"))

val pipeline = 
     extractBadNumbers ~> collectEach(parse)

pipeline.unsafeRun()
```
You will get:
```
List(1, 2, 4)
```


## `filterEach` / `filterEachPar`: keep by predicate

```scala
val extractNumbers = Node(_ => List(1, 2, 3, 4, 5, 6))
val isEven = Node[Int, Boolean](_ % 2 == 0)

val pipeline =
     extractNumbers ~> filterEach(isEven)

pipeline.unsafeRun()
```
You will get:
```
List(2, 4, 6)
```

## Failures

Under an effect, an element failure short-circuits the batch:

```scala
import scala.util.Try

val riskyFunction: Node[Int, Int] = Node(n => if (n == 2) sys.error("boom") else n)

val riskyPipeline =
     extractNumbers ~> eachPar(2)(riskyFunction)

riskyPipeline.compile[Try].unsafeRun()
```
You will get:
```
Failure(...)
```

## Custom batchables

Implement `etl4s.Batchable` to use your own container types:

```scala
import etl4s._

case class Page[A](items: Vector[A], nextCursor: Option[String])

given [A]: Batchable[Page[A], A, Page] with {
  def toSeq(page: Page[A])   = page.items
  def fromElems(xs: Seq[A])  = Page(xs.toVector, None)
  def fromSeq[B](xs: Seq[B]) = Page(xs.toVector, None)
}

val fetchPage = Node(_ => Page(Vector(1, 2, 3), None))

val p = 
     fetchPage ~> eachPar(8)(enrich)
```

## Introspection

A reified batch is still inspectable: the inner step shows up in `.stages`:

```scala
val p =
     extractNumbers ~> eachPar(3)(clean)

p.stages.map(_.name)
```
...which includes `"clean"`.
