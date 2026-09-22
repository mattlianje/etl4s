# Common Patterns

## Chain pipelines

`~>` wires one node's output into the next - read it left to right.

```scala
import etl4s._

val ingest = pullEvents ~> parse ~> validate ~> load
```

## Fan-out to many

Gather independent sources with `&`, then flow the tuple downstream:

```scala
val gather  = fetchUser & fetchOrders & fetchPayments

val profile = gather ~> assemble ~> render
```

`&>` is the concurrent counterpart - parallel once compiled to an effect like `Future`:

```scala
val gather = fetchUser &> fetchOrders &> fetchPayments

gather.compile[Future].unsafeRun()
```

See [Effect polymorphism](effect-polymorphism.md) for `.compile[Id|Try|Future]`
and your own effects. Use `*` / `*>` to pair nodes that take *different* inputs.

## Batch processing

```scala
val ingest = listFiles ~> each(parseFile) ~> load
val fast   = listFiles ~> eachPar(4)(parseFile) ~> load   // up to 4 at a time
```

See [Batch operations](batch.md) for the full set and how they behave per effect.

## Peek mid-pipeline with `.tap`

`.tap` observes the value in flight without changing it - handy for logging:

```scala
val extract   = Extract("s3://events/2026-09-21")
val transform = Transform[String, Int](_.length)
val load      = Load[Int, Unit](rows => save(rows))

val pipeline =
  extract   .tap(path => println(s"pulling $path")) ~>
  transform .tap(rows => println(s"rows: $rows"))   ~>
  load
```

## Sequence side-effects with `>>`

Run setup steps in order, then flow into the real pipeline:

```scala
val nightly = clearStaging >> warmCache >> (extract ~> transform ~> load)
```

## Conditional branching

Each branch is a full pipeline, so the routes read like a whiteboard:

```scala
val route = extractOrder
  .If(_.total > 1000)   (flagForReview ~> enrich ~> load)
  .ElseIf(_.total > 0)  (enrich ~> load)
  .Else                 (reject)
```

## Branch on config, not data

When the route depends only on config, `IfCtx` starts the pipeline on the context directly - no source node. The condition is `Config => Boolean`, so the branch is picked before any data flows:

```scala
case class Config(isBackfill: Boolean, isDryRun: Boolean)

val backfill = Node[Int, String](n => s"backfill:$n")
val dryRun   = Node[Int, String](n => s"dryrun:$n")
val normal   = Node[Int, String](n => s"normal:$n")

val ingest = IfCtx[Config](_.isBackfill)(backfill)
  .ElseIfCtx(_.isDryRun)(dryRun)
  .Else(normal)

ingest.provide(Config(isBackfill = true, isDryRun = false)).unsafeRun(42)
```

## Fallback values with `.onFailure`

Recover inline, then keep flowing:

```scala
val rates   = fetchLiveRates.onFailure(_ => cachedRates)

val convert = rates ~> applyRates ~> load
```

## Retry with backoff

```scala
val fetch  = callPaymentApi.withRetry(maxAttempts = 3, initialDelayMs = 100, backoffFactor = 2.0)

val charge = fetch ~> recordTxn ~> notify
```
