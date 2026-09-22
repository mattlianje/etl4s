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
has a family of `Each` combinators.

They work on `List`, `Vector`, `Seq`,
`Set`, and `Iterable` out of the box (plus `LazyList` on Scala 3), and any
[custom container](#custom-batchables) you teach it about.

| Combinator | What it does |
|------------|--------------|
| `each(sub)` | run `sub` on every element |
| `eachSlice(size)(sub)` | run `sub` on chunks of `size` elements |
| `collectEach(sub)` | run `sub: A => Option[B]`, keep the `Some`s |
| `filterEach(pred)` | keep the elements where `pred` holds |


## `each`: one element at a time

Applies the inner sub-pipeline to every element, in order, preserving the collection type.
Each order is validated and enriched on its own, then the whole batch is written:

```scala
val ingest =
     fetchOrders ~> each(validateOrder ~> enrichOrder) ~> writeOrdersToDB
```

## `eachPar(n)`: up to `n` in flight

Same as `each`, but processes up to `n` elements concurrently.

```scala
val ingest =
     fetchOrders ~> eachPar(8)(validateOrder ~> enrichOrder) ~> writeOrdersToDB
```

!!! note "Concurrency needs a concurrent effect"

    Like `&>` and `*>`, `eachPar` only runs in parallel when you
    [compile to a concurrent effect](effect-polymorphism.md). Under the default
    `Id` interpreter (`.unsafeRun`) it runs sequentially.

## `eachSlice(size)`: whole chunks at a time

Feeds the sub-pipeline chunks of `size` elements instead of single ones.
Ideal for bulk upserts or batched API calls:

```scala
val bulk =
     fetchOrders ~> eachSlice(500)(bulkUpsertOrders) ~> writeReport
```

## `collectEach` / `collectEachPar`: map and drop

When the inner step returns an `Option`, `collectEach` keeps the `Some`s and drops the
`None`s, a batch-flavoured `collect`. Rows that fail to parse simply fall away:

```scala
val load =
     fetchRows ~> collectEach(parseRow) ~> writeOrdersToDB
```

## `filterEach` / `filterEachPar`: keep by predicate

Keeps the elements where the predicate node holds:

```scala
val bigOnly =
     fetchOrders ~> filterEach(isBigOrder) ~> writeReport
```

## Failures

Under an effect, an element failure short-circuits the batch:

```scala
val ingest =
     fetchOrders ~> eachPar(2)(riskyStep)

ingest.compile[Try].unsafeRun()  // Failure(...) on the first element that throws
```

## Custom batchables

Implement `etl4s.Batchable` to run over your own container types:

```scala
import etl4s._

case class Page[A](items: Vector[A], nextCursor: Option[String])

given [A]: Batchable[Page[A], A, Page] with {
  def toSeq(page: Page[A])   = page.items
  def fromElems(xs: Seq[A])  = Page(xs.toVector, None)
  def fromSeq[B](xs: Seq[B]) = Page(xs.toVector, None)
}

val enrichPage =
     fetchPage ~> eachPar(8)(enrichOrder)
```

## Introspection

A reified batch is still inspectable, the inner step shows up in `.stages`:

```scala
val p =
     fetchOrders ~> eachPar(3)(enrichOrder)

p.stages.map(_.name)  // includes "enrichOrder"
```
