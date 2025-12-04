# Conditional Branching

Route data through different pipelines with `If`, `ElseIf`, and `Else`.

```scala
import etl4s._

val router = Node[Int, Int](identity)
  .If(_ > 0)     (Node(_ => "positive"))
  .ElseIf(_ < 0) (Node(_ => "negative"))
  .Else          (Node(_ => "zero"))

router.unsafeRun(5)   // "positive"
router.unsafeRun(-3)  // "negative"
router.unsafeRun(0)   // "zero"
```

## Pipeline Branches

```scala
val router = Node[User, User](identity)
  .If(_.tier == "premium")      (validate ~> enrich ~> toPremiumResult)
  .ElseIf(_.tier == "standard") (validate ~> toStandardResult)
  .Else                         (toGuestResult)
```

## With Parallel Fan-out

```scala
val router = Node[User, User](identity)
  .If(_.wantsDetails) ((identity & loadMetrics & loadHistory) ~> toFullProfile)
  .Else               (toSimpleProfile)
```

## Config-Aware

```scala
val router = source
  .If(cfg => n => n >= cfg.threshold) (premiumPath)
  .Else                               (standardPath)

router.provide(Config(100)).unsafeRun(150)  // premium
router.provide(Config(100)).unsafeRun(50)   // standard
```

## Scala 2 vs Scala 3

The API is identical, but Scala 3 supports heterogeneous branches.

**Scala 2** - all branches must return the same type:
```scala
val router = Node[Int, Int](identity)
  .If(_ > 0)     (Node(n => s"pos-$n"))
  .ElseIf(_ < 0) (Node(n => s"neg-$n"))
  .Else          (Node(n => s"zero"))
// All branches return String
```

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
val router = source
  .If(cfg => _.startsWith("db:")) (dbBranch)    // needs DbConfig
  .Else                           (cacheBranch) // needs CacheConfig
// Must provide: DbConfig & CacheConfig
```
