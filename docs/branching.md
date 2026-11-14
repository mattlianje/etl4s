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

Send data down different pipelines with `If`, `ElseIf` and `Else`. You can branch on the data itself, or on config.

## Branch on data

The condition runs on the input, and only the matching branch runs:

```scala
val classify = score
  .If(_ > 0)     (tagPositive)
  .ElseIf(_ < 0) (tagNegative)
  .Else          (tagZero)
```

With an `.Else` every input is covered. Without one, anything that doesn't match passes straight through:

```scala
val maybeBoost = enrich.If(_.score > 10)(applyBoost)
```

## Start with a branch

`If[A]` starts a pipeline with the branch itself, no upstream node needed:

```scala
val ship = If[Order](_.isRush)(expedite).Else(standard) ~> notify
```

## Use whole pipelines as branches

A branch can be any pipeline, fan-outs included:

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

When the decision needs config too, write the condition as `(cfg: Config) => (data: A) => Boolean`:

```scala
val route = source
  .If((cfg: Config) => (n: Int) => n < cfg.threshold) (formatBelow)
  .Else                                               (formatAbove)

route.provide(Config(10)).unsafeRun(5) // "below:5"
```

## Branch on config alone

When only the config matters, use `IfCtx` / `ElseIfCtx`, where the condition is just `Config => Boolean`.
`IfCtx[Config]` can start a pipeline on its own, no source node needed:

```scala
val ingest =
  IfCtx[Config](_.isBackfill)(readSnapshot ~> replay ~> load)
    .ElseIfCtx(_.isDryRun)   (readStream ~> validate ~> logOnly)
    .Else                    (readStream ~> validate ~> load)

ingest.provide(Config(isBackfill = true, isDryRun = false)).unsafeRun(batch)
```

If you already have an upstream node, call `.IfCtx` on it instead:

```scala
val ingest = source
  .IfCtx(_.isBackfill)  (readSnapshot ~> replay ~> load)
  .ElseIfCtx(_.isDryRun)(readStream ~> validate ~> logOnly)
  .Else                 (readStream ~> validate ~> load)
```

## Scala 2 vs Scala 3

The API is the same on both, but Scala 3 is a bit more flexible.

**Scala 3**: branches can return different types (you get a union):

```scala
val router = score
  .If(_ > 0)     (toLabel)    // String
  .ElseIf(_ < 0) (negate)     // Int
  .Else          (toDouble)   // Double
```

The result type is `Node[Int, String | Int | Double]`.

**Scala 3**: branches can need different configs. Mix one that needs `DbConfig` with one that needs `CacheConfig`
and you provide `DbConfig & CacheConfig`.

!!! note "Scala 2"
    All branches must return the same type and need the same config:
    ```scala
    val router = score
      .If(_ > 0)     (posLabel)
      .ElseIf(_ < 0) (negLabel)
      .Else          (zeroLabel)
    ```
