---
api:
  - sig: ".If(pred)(branch)"
  - sig: ".ElseIf(pred)(branch)"
  - sig: ".Else(branch)"
  - sig: "If[A](pred)(branch)"
  - sig: "IfCtx[C](pred)(branch)"
  - sig: ".IfCtx(pred)(branch)"
  - sig: ".ElseIfCtx(pred)(branch)"
---

# Conditional branching

Route data down different pipelines with `If`, `ElseIf`, and `Else`. Branch on the data flowing through, or on outside config. Each branch is a full pipeline, so a router reads like a whiteboard.

## Branch on data

The predicate runs against the input, and only the matching branch fires:

```scala
val classify = score
  .If(_ > 0)     (tagPositive)
  .ElseIf(_ < 0) (tagNegative)
  .Else          (tagZero)
```

Close with `.Else` and it is exhaustive. Drop the `.Else` and unmatched input flows straight through:

```scala
val maybeBoost = enrich.If(_.score > 10)(applyBoost) // no Else: the rest passes through
```

## Start with a branch

`If[A]` opens a pipeline on the branch itself, no upstream node needed:

```scala
val ship = If[Order](_.isRush)(expedite).Else(standard) ~> notify
```

## Branches are pipelines

Any branch can be a whole pipeline, fan-out and all:

```scala
val offers = extractUser
  .If(_.tier == "premium")      (validate ~> enrich ~> premiumOffer)
  .ElseIf(_.tier == "standard") (validate ~> standardOffer)
  .Else                         (guestNotice)
```

```scala
val profile = loadUser
  .If(_.wantsDetails) ((self & loadMetrics & loadHistory) ~> fullProfile)
  .Else               (simpleProfile)
```

Swap `&` for `&>` to fan out concurrently under an effect.

## Branch on config and data

When the decision needs config too, take a typed condition `(cfg: Config) => (data: A) => Boolean`:

```scala
val route = source
  .If((cfg: Config) => (n: Int) => n < cfg.threshold) (formatBelow)
  .Else                                               (formatAbove)

route.provide(Config(10)).unsafeRun(5) // "below:5"
```

## Branch on config alone

When only the config matters and the data is irrelevant, use `IfCtx` / `ElseIfCtx` - the condition is just `Config => Boolean`. `IfCtx[Config]` starts the pipeline on the context itself, no source node:

```scala
val ingest =
  IfCtx[Config](_.isBackfill)(readSnapshot ~> replay ~> load)
    .ElseIfCtx(_.isDryRun)   (readStream ~> validate ~> logOnly)
    .Else                    (readStream ~> validate ~> load)

ingest.provide(Config(isBackfill = true, isDryRun = false)).unsafeRun(batch)
```

Already have an upstream Reader? Call `.IfCtx` on it instead:

```scala
val ingest = source
  .IfCtx(_.isBackfill)  (readSnapshot ~> replay ~> load)
  .ElseIfCtx(_.isDryRun)(readStream ~> validate ~> logOnly)
  .Else                 (readStream ~> validate ~> load)
```

## Scala 2 vs Scala 3

The API is identical across versions, but Scala 3's type system enables more flexibility.

**Scala 3**: branches can return different types (union):

```scala
val router = score
  .If(_ > 0)     (toLabel)    // String
  .ElseIf(_ < 0) (negate)     // Int
  .Else          (toDouble)   // Double
```

The result type is `Node[Int, String | Int | Double]`.

**Scala 3**: config-aware branches accumulate their config via intersection (`&`):
mixing branches that need `DbConfig` and `CacheConfig` yields a pipeline that must be provided `DbConfig & CacheConfig`.

!!! note "Scala 2"
    All branches must return the same type, and share the same config type:
    ```scala
    val router = score
      .If(_ > 0)     (posLabel)
      .ElseIf(_ < 0) (negLabel)
      .Else          (zeroLabel)
    ```
