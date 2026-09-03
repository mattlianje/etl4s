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

`.If(pred)(branch)` checks the predicate against the input, and only the matching branch runs. Add more cases with `.ElseIf`, and a fallback with `.Else`:

```scala
import etl4s._

val classify = Node[Int, Int](identity)
  .If(_ > 0)     (Node[Int, String](_ => "positive"))
  .ElseIf(_ < 0) (Node[Int, String](_ => "negative"))
  .Else          (Node[Int, String](_ => "zero"))

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

case class User(tier: String, name: String)

val extractUser  = Node[User, User](identity)
val validateUser = Node[User, User](identity)
val enrichUser   = Node[User, User](u => u.copy(name = u.name.toUpperCase))

val toPremiumOffer  = Node[User, String](u => s"premium offer for ${u.name}")
val toStandardOffer = Node[User, String](u => s"standard offer for ${u.name}")
val toGuestNotice   = Node[User, String](u => s"guest notice for ${u.name}")

val pipeline = extractUser
  .If(_.tier == "premium")      (validateUser ~> enrichUser ~> toPremiumOffer)
  .ElseIf(_.tier == "standard") (validateUser ~> toStandardOffer)
  .Else                         (toGuestNotice)

pipeline.unsafeRun(User("premium", "ada"))
pipeline.unsafeRun(User("standard", "bob"))
pipeline.unsafeRun(User("none", "cleo"))
```

You will get:

```
"premium offer for ADA"
"standard offer for bob"
"guest notice for cleo"
```

## Combining with fan-out

Branches can include parallel operations using `&` (or `&>` under a concurrent effect):

```scala
import etl4s._

case class User(id: Int, wantsDetails: Boolean)

val identityN   = Node[User, User](identity)
val loadMetrics = Node[User, Int](_.id * 10)
val loadHistory = Node[User, Int](_.id * 100)

val toFullProfile   = Node[(User, Int, Int), String] { case (u, m, h) => s"full:${u.id}:$m:$h" }
val toSimpleProfile = Node[User, String](u => s"simple:${u.id}")

val router = identityN
  .If(_.wantsDetails) ((identityN & loadMetrics & loadHistory) ~> toFullProfile)
  .Else               (toSimpleProfile)

router.unsafeRun(User(1, wantsDetails = true))
router.unsafeRun(User(2, wantsDetails = false))
```

You will get:

```
"full:1:10:100"
"simple:2"
```

## Config-aware branching

When the branch decision needs configuration, branch over a `Reader[Config, Node[A, B]]` source. There is no three-parameter `Node[Config, A, B]` constructor. A config-aware source is always a `Reader`.

There are two ways to get one: write a `Reader[Config, Node[A, B]]` directly, or make an existing node config-aware with `.requires[Config]`.

Use a typed condition `(cfg: Config) => (data: A) => Boolean` when the decision depends on both config and data:

```scala
import etl4s._

case class Config(threshold: Int)

// A Reader source, written directly:
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

Making an existing node config-aware with `.requires[Config]` gives the same `Reader` shape:

```scala
import etl4s._

case class Config(threshold: Int)

val source = Node[Int, Int](identity).requires[Config] { _ => n => n }
// source: Reader[Config, Node[Int, Int]]
```

## Context-only branching

When the decision depends only on configuration and not on the data flowing through, use `IfCtx` / `ElseIfCtx`. The condition is just `Config => Boolean`:

```scala
import etl4s._

case class Config(isBackfill: Boolean, isDryRun: Boolean)

val source   = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
val backfill = Node[Int, String](n => s"backfill:$n")
val dryRun    = Node[Int, String](n => s"dryrun:$n")
val normal   = Node[Int, String](n => s"normal:$n")

val pipeline = source
  .IfCtx(_.isBackfill)(backfill)
  .ElseIfCtx(_.isDryRun)(dryRun)
  .Else(normal)

pipeline.provide(Config(isBackfill = true,  isDryRun = false)).unsafeRun(42)
pipeline.provide(Config(isBackfill = false, isDryRun = true )).unsafeRun(42)
pipeline.provide(Config(isBackfill = false, isDryRun = false)).unsafeRun(42)
```

You will get:

```
"backfill:42"
"dryrun:42"
"normal:42"
```

This is cleaner than the curried `(cfg: Config) => (_: Int) => cfg.isBackfill` form when the data value is irrelevant to the condition.

## Automatic Reader lifting

Branches can freely mix plain `Node` and `Reader[Config, Node]`. etl4s automatically lifts plain `Node` branches into `Reader`, so you never wrap them by hand, even when mixing with `IfCtx`/`ElseIfCtx`:

```scala
import etl4s._

case class Config(threshold: Int)

val source      = Reader[Config, Node[Int, Int]] { _ => Node[Int, Int](identity) }
val belowThresh = Reader[Config, Node[Int, String]] { cfg =>
  Node(n => s"below-${cfg.threshold}:$n")
}
val aboveThresh = Node[Int, String](n => s"above:$n")  // plain Node, auto-lifted
val negative    = Node[Int, String](n => s"negative:$n")

val pipeline = source
  .If((_: Int) < 0)                                   (negative)
  .ElseIf((cfg: Config) => (n: Int) => n < cfg.threshold)(belowThresh)
  .Else                                               (aboveThresh)

pipeline.provide(Config(10)).unsafeRun(-5)
pipeline.provide(Config(10)).unsafeRun(5)
pipeline.provide(Config(10)).unsafeRun(15)
```

You will get:

```
"negative:-5"
"below-10:5"
"above:15"
```

## Under an effect

Branching folds correctly through `.compile[F]`, so it works under any effect (`Try`, `Future`, `Id`, ...):

```scala
import etl4s._
import scala.util.Try

val classify = Node[Int, Int](identity)
  .If(_ > 0)     (Node[Int, String](_ => "positive"))
  .Else          (Node[Int, String](_ => "non-positive"))

classify.compile[Try].unsafeRun(5)
classify.compile[Try].unsafeRun(-1)
```

You will get:

```
Success("positive")
Success("non-positive")
```

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
    // All branches return String
    ```
