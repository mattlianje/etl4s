# Conditional Branching

Route data through different pipelines using `If`, `ElseIf`, and `Else`. Branch on the data itself or on configuration.

```scala
import etl4s._

case class JobConfig(isBackfill: Boolean)

val pipeline = extract ~> validate
  .If(cfg => _ => cfg.isBackfill) (fullLoad ~> dedupe ~> save)
  .Else                           (deltaLoad ~> save)
```

Branch on data:

```scala
val classify = Node[Int, Int](identity)
  .If(_ > 0)     (Node(_ => "positive"))
  .ElseIf(_ < 0) (Node(_ => "negative"))
  .Else          (Node(_ => "zero"))

classify.unsafeRun(5)   // "positive"
classify.unsafeRun(-3)  // "negative"
classify.unsafeRun(0)   // "zero"
```

The condition is checked against the input, and only the matching branch executes.

## Composing Pipelines in Branches

Each branch can be a full pipeline, not just a single node:

```scala
val router = Node[User, User](identity)
  .If(_.tier == "premium")      (validate ~> enrich ~> toPremiumResult)
  .ElseIf(_.tier == "standard") (validate ~> toStandardResult)
  .Else                         (toGuestResult)
```

## Combining with Fan-out

Branches can include parallel operations using `&` or `&>`:

```scala
val router = Node[User, User](identity)
  .If(_.wantsDetails) ((identity & loadMetrics & loadHistory) ~> toFullProfile)
  .Else               (toSimpleProfile)
```

## Config-Aware Branching

When your pipeline uses configuration, conditions can access it too:

```scala
case class Config(threshold: Int)

val router = Node[Config, Int, Int](identity)
  .If(cfg => n => n >= cfg.threshold) (premiumPath)
  .Else                               (standardPath)

router.provide(Config(100)).unsafeRun(150)  // premium
router.provide(Config(100)).unsafeRun(50)   // standard
```

## Context-Only Branching

Use `IfCtx` and `ElseIfCtx` when the branch decision depends only on config, not the data flowing through:

```scala
case class Config(isBackfill: Boolean, isDryRun: Boolean)

val source   = Reader[Config, Node[Int, Int]] { _ => Node(identity) }
val backfill = Node[Int, String](n => s"backfill:$n")
val dryRun   = Node[Int, String](n => s"dryrun:$n")
val normal   = Node[Int, String](n => s"normal:$n")

val pipeline = source
  .IfCtx(_.isBackfill)(backfill)
  .ElseIfCtx(_.isDryRun)(dryRun)
  .Else(normal)

pipeline.provide(Config(isBackfill = true, isDryRun = false)).unsafeRun(42)
// "backfill:42"
```

This is cleaner than the curried form `cfg => _ => cfg.isBackfill` when the data value is irrelevant to the condition.

## Automatic Reader Lifting

Branches can freely mix plain `Node` and `Reader` branches. etl4s automatically lifts `Node` branches into `Reader` so you don't have to wrap them manually:

```scala
case class Config(threshold: Int)

val source      = Reader[Config, Node[Int, Int]] { _ => Node(identity) }
val belowThresh = Reader[Config, Node[Int, String]] { cfg =>
  Node(n => s"below-${cfg.threshold}:$n")
}
val aboveThresh = Node[Int, String](n => s"above:$n")  // plain Node, auto-lifted

val pipeline = source
  .If((cfg: Config) => (n: Int) => n < cfg.threshold)(belowThresh)
  .Else(aboveThresh)

pipeline.provide(Config(10)).unsafeRun(5)   // "below-10:5"
pipeline.provide(Config(10)).unsafeRun(15)  // "above:15"
```

In Scala 3, config types accumulate via intersection (`&`) and output types via union (`|`). In Scala 2, all branches share the same config and output types.

## Scala 2 vs Scala 3

The API is identical across versions, but Scala 3's type system enables more flexibility.

**Scala 3** - branches can return different types (union):

```scala
val router = Node[Int, Int](identity)
  .If(_ > 0)     (Node(n => s"pos-$n"))    // String
  .ElseIf(_ < 0) (Node(n => n * -1))       // Int
  .Else          (Node(n => n.toDouble))   // Double
// Result type: String | Int | Double
```

**Scala 3** - branches can require different configs (intersection):

```scala
val router = Node[String, String](identity)
  .If(cfg => _.startsWith("db:")) (dbBranch)    // needs DbConfig
  .Else                           (cacheBranch) // needs CacheConfig
// Must provide: DbConfig & CacheConfig
```

!!! note "Scala 2"
    All branches must return the same type:
    ```scala
    val router = Node[Int, Int](identity)
      .If(_ > 0)     (Node(n => s"pos-$n"))
      .ElseIf(_ < 0) (Node(n => s"neg-$n"))
      .Else          (Node(_ => "zero"))
    // All branches return String
    ```
