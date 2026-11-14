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

Often you want to run a sub-pipeline over every element of a collection. That's what the `each` family is for.

They work on `List`, `Vector`, `Seq`, `Set` and `Iterable` out of the box (plus `LazyList` on Scala 3), and on
[your own containers](#batch-over-your-own-container-types) too.

| Combinator | What it does |
|------------|--------------|
| `each(sub)` | run `sub` on every element |
| `eachSlice(size)(sub)` | run `sub` on chunks of `size` elements |
| `collectEach(sub)` | run `sub: A => Option[B]`, keep the `Some`s |
| `filterEach(pred)` | keep the elements where `pred` holds |


## Process every element with `each`

Runs the sub-pipeline on each element in order, and keeps the collection type. Here each order is validated and
enriched on its own, then the whole batch is written:

```scala
val ingest =
     fetchOrders ~> each(validateOrder ~> enrichOrder) ~> writeOrdersToDB
```

## Process elements in parallel with `eachPar(n)`

Same as `each`, but works on up to `n` elements at once:

```scala
val ingest =
     fetchOrders ~> eachPar(8)(validateOrder ~> enrichOrder) ~> writeOrdersToDB
```

!!! note "Concurrency needs a concurrent effect"

    Like `&>` and `*>`, `eachPar` only runs in parallel when you
    [compile to a concurrent effect](effect-polymorphism.md). Under the default
    `Id` interpreter (`.unsafeRun`) it runs sequentially.

## Process chunks with `eachSlice(size)`

Hands the sub-pipeline chunks of `size` elements instead of one at a time. Good for bulk upserts or batched API calls:

```scala
val bulk =
     fetchOrders ~> eachSlice(500)(bulkUpsertOrders) ~> writeReport
```

## Drop the misses with `collectEach` / `collectEachPar`

When the inner step returns an Option, `collectEach` keeps the Somes and drops the Nones. Here rows that fail to
parse just fall away:

```scala
val load =
     fetchRows ~> collectEach(parseRow) ~> writeOrdersToDB
```

## Filter elements with `filterEach` / `filterEachPar`

Keeps the elements where the predicate holds:

```scala
val bigOnly =
     fetchOrders ~> filterEach(isBigOrder) ~> writeReport
```

## Handle failures

Under an effect, the first element that fails stops the whole batch:

```scala
val ingest =
     fetchOrders ~> eachPar(2)(riskyStep)

ingest.compile[Try].unsafeRun()  // Failure(...) on the first element that throws
```

## Batch over your own container types

Implement `etl4s.Batchable` for your type:

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

## Inspect a batch

A batch is still a value you can inspect, and the inner step shows up in `.stages`:
