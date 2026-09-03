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

Run a sub-pipeline over every element of a collection with `each`, `eachPar`,
and `eachSlice`. They work on `List`, `Vector`, `Seq`, `Set`, and `Iterable`
out of the box.

```scala
import etl4s._

val clean  = Transform[Int, Int](_ + 1)
val enrich = Transform[Int, String](n => s"v$n")

val fetch  = Extract(_ => List(1, 2, 3))
```

## `each`: one element at a time

Apply the inner sub-pipeline to each element, sequentially.

```scala
val pipeline = fetch ~> each(clean ~> enrich)

pipeline.unsafeRun(())
```
You will get:
```
List("v2", "v3", "v4")
```

The concrete collection type is preserved through the fold:

```scala
val fetchV = Extract(_ => Vector(1, 2, 3))
(fetchV ~> each(clean)).unsafeRun(())
```
You will get:
```
Vector(2, 3, 4)
```

## `eachPar(n)`: up to `n` in flight

Same as `each`, but processes up to `n` elements concurrently.

```scala
val pipeline = fetch ~> eachPar(8)(clean ~> enrich)
```

!!! note "Concurrency needs a concurrent effect"
    Like `&>` and `*>`, `eachPar` only runs in parallel when you
    [compile to a concurrent effect](effect-polymorphism.md). Under the default
    `Id` interpreter (`.unsafeRun`) it runs sequentially, order preserved, no
    threads.

    ```scala
    import scala.concurrent.Future
    import scala.concurrent.ExecutionContext.Implicits.global

    pipeline.compile[Future].unsafeRun(())   // up to 8 elements at once
    ```

## `eachSlice(size)`: whole chunks at a time

Feed the sub-pipeline chunks of `size` elements instead of single elements.
Ideal for bulk upserts or batched API calls.

```scala
val bulkUpsert = Load[List[Int], Unit](chunk => println(s"upserting ${chunk.size} rows"))

val pipeline = fetch ~> eachSlice(500)(bulkUpsert)
```

## `collectEach` / `collectEachPar`: map and drop

When the inner step returns an `Option`, `collectEach` keeps the `Some` values
and drops the `None`s, a batch-flavoured `collect`. The concrete collection type
is preserved.

```scala
val parse = Transform[String, Option[Int]](s => scala.util.Try(s.toInt).toOption)

val pipeline = Extract(_ => List("1", "2", "oops", "4")) ~> collectEach(parse)

pipeline.unsafeRun(())
```
You will get:
```
List(1, 2, 4)
```

`collectEachPar(n)` is the same, running up to `n` elements concurrently under a
concurrent effect (see the note above).

## `filterEach` / `filterEachPar`: keep by predicate

Keep only the elements for which a predicate node holds, a batch-flavoured
`filter`:

```scala
val isEven = Transform[Int, Boolean](_ % 2 == 0)

val evens = Extract(_ => List(1, 2, 3, 4, 5, 6)) ~> filterEach(isEven)

evens.unsafeRun(())
```
You will get:
```
List(2, 4, 6)
```

`filterEachPar(n)` runs up to `n` predicates concurrently under a concurrent effect.

## Failures

Under an effect, an element failure short-circuits the batch:

```scala
import scala.util.Try

val boom = fetch ~> eachPar(2)(Transform[Int, Int](n => if (n == 2) sys.error("boom") else n))

boom.compile[Try].unsafeRun(())
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

val fetchPage = Extract(_ => Page(Vector(1, 2, 3), None))

fetchPage ~> eachPar(8)(enrich)
```

## Introspection

A reified batch is still inspectable: the inner step shows up in `.stages`:

```scala
val p = fetch ~> eachPar(3)(clean.withName("clean"))
p.stages.map(_.name)
```
...which includes `"clean"`.
