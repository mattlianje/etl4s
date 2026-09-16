---
api:
  - sig: ".If(pred)(branch)"
  - sig: ".ElseIf(pred)(branch)"
  - sig: ".Else(branch)"
  - sig: "If[A](pred)(branch)"
  - sig: ".IfCtx(pred)(branch)"
  - sig: ".ElseIfCtx(pred)(branch)"
---

# Conditional branching

Route data through different pipelines with `If`, `ElseIf`, and `Else`. You can branch on the data flowing through the pipeline, or on external configuration/context.

## Branch on data

`.If(pred)(branch)` checks the predicate against the input, and only the matching branch runs. The compiler will ensure your conditional 
is fully exhaustive with a closing `Else`

Suppose we have:
```scala
val positive = Node[Int, String](_ => "positive")
val negative = Node[Int, String](_ => "negative")
val zero = Node[Int, String](_ => "zero")
```

We can do:

```scala
import etl4s._

val classify = Node[Int, Int](identity)
  .If(_ > 0)     (positive)
  .ElseIf(_ < 0) (negative)
  .Else          (zero)

classify.unsafeRun(5)
classify.unsafeRun(-3)
classify.unsafeRun(0)
```

You will get:

```
"positive"
"negative"
"zero"
```

## Standalone `If` starter

`If[A](pred)(branch)` starts a branch directly, without an upstream node. It is handy at the head of a pipeline:

```scala
import etl4s._

val double   = Node[Int, Int](_ * 2)
val negate   = Node[Int, Int](-_)
val describe = Node[Int, String](n => s"= $n")

val pipeline = If[Int](_ > 0)(double).Else(negate) ~> describe

pipeline.unsafeRun(5)
pipeline.unsafeRun(-3)
```

You will get:

```
"= 10"
"= 3"
```

## Partial builders pass through

A builder without a trailing `.Else` is still a usable `Node`. Unmatched input simply passes through unchanged:

```scala
import etl4s._

val expedite = Node[Int, Int](_ + 100)

val maybe: Node[Int, Int] = If[Int](_ > 10)(expedite)

maybe.unsafeRun(20)
maybe.unsafeRun(5)
```

You will get:

```
120  (matched)
5    (passed through)
```

## Composing pipelines in branches

Each branch can be a full pipeline, not just a single node:

```scala
import etl4s._

val pipeline = extractUser
  .If(_.tier == "premium")      (validateUser ~> enrichUser ~> toPremiumOffer)
  .ElseIf(_.tier == "standard") (validateUser ~> toStandardOffer)
  .Else                         (toGuestNotice)
```

## Combining with fan-out

Branches can include parallel operations using `&` (or `&>` under a concurrent effect):

```scala
import etl4s._

val router = identityN
  .If(_.wantsDetails) ((identityN & loadMetrics & loadHistory) ~> toFullProfile)
  .Else               (toSimpleProfile)
```


## Config-aware branching

Use a typed condition `(cfg: Config) => (data: A) => Boolean` when the decision depends on both config and data:

```scala
import etl4s._

case class Config(threshold: Int)

val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
val formatBelow = Reader[Config, Node[Int, String]] { _ => Node(n => s"below:$n") }
val formatAbove = Reader[Config, Node[Int, String]] { _ => Node(n => s"above:$n") }

val pipeline = source
  .If((cfg: Config) => (n: Int) => n < cfg.threshold) (formatBelow)
  .Else                                               (formatAbove)

pipeline.provide(Config(10)).unsafeRun(5)
pipeline.provide(Config(10)).unsafeRun(15)
```

You will get:

```
"below:5"
"above:15"
```


## Context-only branching

When the decision depends only on configuration and not on the data flowing through, use `IfCtx` / `ElseIfCtx`. The condition is just `Config => Boolean`:

```scala
import etl4s._

case class Config(isBackfill: Boolean, isDryRun: Boolean)

val source   = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
val backfill = Node[Int, String](n => s"backfill:$n")
val dryRun   = Node[Int, String](n => s"dryrun:$n")
val normal   = Node[Int, String](n => s"normal:$n")

val pipeline = source
  .IfCtx(_.isBackfill)(backfill)
  .ElseIfCtx(_.isDryRun)(dryRun)
  .Else(normal)
```

This is cleaner than the curried `(cfg: Config) => (_: Int) => cfg.isBackfill` form when the data value is irrelevant to the condition.

## Scala 2 vs Scala 3

The API is identical across versions, but Scala 3's type system enables more flexibility.

**Scala 3**: branches can return different types (union):

```scala
val router = Node[Int, Int](identity)
  .If(_ > 0)     (Node(n => s"pos-$n"))    // String
  .ElseIf(_ < 0) (Node(n => n * -1))       // Int
  .Else          (Node(n => n.toDouble))   // Double
```

The result type is `Node[Int, String | Int | Double]`.

**Scala 3**: config-aware branches accumulate their config via intersection (`&`):
mixing branches that need `DbConfig` and `CacheConfig` yields a pipeline that must be provided `DbConfig & CacheConfig`.

!!! note "Scala 2"
    All branches must return the same type, and share the same config type:
    ```scala
    val router = Node[Int, Int](identity)
      .If(_ > 0)     (Node(n => s"pos-$n"))
      .ElseIf(_ < 0) (Node(n => s"neg-$n"))
      .Else          (Node(_ => "zero"))
    ```
