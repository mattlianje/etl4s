# Common Patterns

## Chain pipelines

`~>` wires one node's output into the next - read it left to right.

```scala
import etl4s._

val ingest = pullEvents ~> parse ~> validate ~> load
```

A pipeline is itself a node, so pipelines chain the same way:

```scala
val ingest  = pullEvents ~> parse
val publish = validate ~> load

val nightly = ingest ~> publish
```

## Fan-out to many

Gather independent sources with `&`, then flow the tuple downstream:

```scala
val profile = (fetchUser & fetchOrders) ~> gather ~> assemble ~> render
```

`&>` is the concurrent counterpart - parallel once compiled to an effect like `Future`:

```scala
val fetchAll = fetchUser &> fetchOrders &> fetchPayments

fetchAll.compile[Future].unsafeRun()
```

See [Effect polymorphism](effect-polymorphism.md) for `.compile[Id|Try|Future]`
and your own effects. Use `*` / `*>` to pair nodes that take different inputs.

## Batch processing

```scala
val ingest = listFiles ~> each(parseFile) ~> load
val fast   = listFiles ~> eachPar(4)(parseFile) ~> load // up to 4 at a time
```

See [Batch operations](batch.md) for the full set and how they behave per effect.

## Peek mid-pipeline with `.tap`

`.tap` observes the value in flight without changing it - handy for logging:

```scala
val pipeline =
     extract.tap(path => println(s"pulling $path")) ~>
     transform.tap(rows => println(s"rows: $rows")) ~>
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

## Route an `Either` with `|`

When a step returns an `Either`, `|` sends each side to its own branch:

```scala
val ingest = parse ~> validate ~> (quarantine | load)
```

`Left`s go to `quarantine`, `Right`s go to `load`.

## Branch on config, not data

When the route depends only on config, `IfCtx` starts the pipeline on the context directly - no source node. The condition is `Config => Boolean`, so the branch is picked before any data flows:

```scala
case class Config(isBackfill: Boolean, isDryRun: Boolean)

val ingest = IfCtx[Config](_.isBackfill)(replayArchive)
  .ElseIfCtx(_.isDryRun)(logOnly)
  .Else(ingestLive)

ingest.provide(Config(isBackfill = true, isDryRun = false)).unsafeRun(42)
```

## Inject config with `.requires`

Declare what a step needs, wire the pipeline as usual, then `.provide` once at the edge:

```scala
val load = Load[Rows, Unit].requires[DbConfig] { db => rows => write(db.url, rows) }

val nightly = extract ~> transform ~> load

nightly.provide(prodDb).unsafeRun()
```

See [Configuration](config.md) for more.

## Fall back to another node with `<|>`

If the first node throws, the second runs on the same input:

```scala
val rates = fetchLiveRates <|> readCachedRates

val convert = rates ~> applyRates ~> load
```

To recover to a plain value instead, use `.onFailure`:

```scala
val rates = fetchLiveRates.onFailure(_ => Rates.empty)
```

## Retry with backoff

```scala
val fetch  = 
     callPaymentApi.withRetry(maxAttempts = 3, initialDelayMs = 100, backoffFactor = 2.0)

val charge = fetch ~> recordTxn ~> notifyCustomer
```
