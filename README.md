<div align="right">
  <sub><em>Part of <a href="https://github.com/mattlianje/d4"><img src="https://raw.githubusercontent.com/mattlianje/d4/master/pix/d4.png" width="23"></a> <a href="https://github.com/mattlianje/d4">d4</a></em></sub>
</div>

<p align="center">
  <img src="pix/etl4s-2.png" width="700">
</p>

# <img src="pix/etl4s-logo.png" width="50"> etl4s
**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency library for writing type-safe, beautiful ✨🍰  data flows in functional Scala.
Battle-tested at [Instacart](https://www.instacart.com/)

📖 **[Full documentation](https://mattlianje.github.io/etl4s/)**

## Installation

**etl4s** is on MavenCentral and cross-built for Scala 2.12, 2.13, 3.x
```scala
"xyz.matthieucourt" %% "etl4s" % "1.9.1"
```
Or try in REPL:
```bash
scala-cli repl --scala 3 --dep xyz.matthieucourt:etl4s_3:1.9.1
```

All you need:
```scala
import etl4s._
```

## Your pipeline as a DAG

Here is a daily orders pipeline. It reads raw orders and FX rates, converts every amount to USD,
then loads the result to the warehouse and posts a summary to Slack.

```scala
import etl4s._

val readOrders = Extract("SELECT id, amount, currency FROM orders")
val fetchRates = Extract(Map("EUR" -> 1.08, "GBP" -> 1.27))

val normalize = Transform[(String, Map[String, Double]), List[Order]] { 
    case (raw, rates) =>
      parseOrders(raw).map(_.toUsd(rates))
}

val writeWarehouse = Load[List[Order], Unit](orders => warehouse.insert(orders))
val notifySlack    = Load[List[Order], Unit](orders => slack.post(s"Loaded ${orders.size} orders"))

val dailyOrders =
  (readOrders & fetchRates) ~> normalize ~> (writeWarehouse & notifySlack)

dailyOrders.unsafeRun()
```

Reading it top to bottom:

1. `readOrders` and `fetchRates` both run and hand their results in as a pair (`&`).
2. `normalize` parses the rows and converts every amount to USD.
3. The result loads two ways at once: into the warehouse and as a Slack summary (`&`).

The whole flow is a plain value. Nothing runs until `unsafeRun()`.

## Core Concepts
**etl4s** has one core building block:
```scala
Node[-In, +Out]
```
A Node wraps a lazily-evaluated function `In => Out`. Chain them with `~>` to build pipelines.

To improve readability and express intent, **etl4s** defines four aliases: `Extract`, `Transform`, `Load` and `Pipeline`. All behave the same under the hood.

```scala
val step = Transform[String, Int](_.length)
step("hello")
```

## Operators

etl4s uses a few simple operators to build pipelines:

| Operator | Name | What it does |
|----------|------|--------------|
| `~>` | Chain | `a ~> b` - output of `a` feeds into `b` |
| `&` / `&>` | Fan-out | `a & b` - run both with the same input (`&>` runs them concurrently) |
| `*` / `*>` | Product | `a * b` - run on different inputs (`*>` runs them concurrently) |
| `>>` | Sequence | `a >> b` - run in order, keep `b`'s result |
| <code>&#124;</code> | Fan-in | <code>a &#124; b</code> - route an `Either` input to the matching branch |
| `+` | Choice | `a + b` - route an `Either` input through independent branches |
| <code>&lt;&#124;&gt;</code> | Fallback | <code>a &lt;&#124;&gt; b</code> - if `a` throws, run `b` on the same input |

## Of note

- Ultimately, these nodes and pipelines are just reifications of functions and values with a few extra niceties.
- Chaotic, framework-coupled ETL codebases that grow without an imposed discipline drive dev teams and data orgs to their knees.
- **etl4s** is simply a library to guide programmers to structure their code as clean, easy to maintain
graphs with typed, declarative endpoints that are easy to refactor and reason about in terms of business logic

## What etl4s is NOT
- A scheduler, no cron, no runtime to deploy
- A DAG backend, no workers, no state store
- A "framework": it is merely a zero-dep lib to structure code

It won't replace Airflow, Dagster, or Spark. You can use it anywhere
data flows through functions: Spark jobs, streaming, web-server dataflows, a local script.

## Refactoring is a one-line diff

Suppose you now need to dedupe orders before they are written. 
You just write a new `Node` and drop it into the chain with `~>`.

```scala
val dedupe = Transform[List[Order], List[Order]](_.distinctBy(_.id))

val dailyOrders =
  (readOrders & fetchRates) ~> normalize ~> dedupe ~> (writeWarehouse & notifySlack)
```

Because the pipeline is a value with explicit input and output types, the compiler checks the new
wiring for you. If `dedupe` didn't fit, it wouldn't compile.

## Pipelines are values you can inspect

A pipeline is a value you can look at before running it. Every `Node` captures its shape, its in/out
types, and its enclosing `val` name at compile time. `.toDot` renders a Graphviz graph, `.toMermaid`
a Mermaid one.

```scala
dailyOrders.toDot
```

<!-- TODO: regenerate this SVG from dailyOrders.toDot so it matches the running example -->
<p align="center">
  <img src="pix/pipeline-example.svg" width="500">
</p>

Because the wiring is data, not just control flow, you can inspect it before it ever runs:

```scala
/* Unit-test the shape */
dailyOrders.stages.map(_.name)

/* Govern architecture */
require(!dailyOrders.stages.exists(_.fullName.startsWith("com.acme.legacy")))

/* Generate docs at build-time that never drift */
os.write.over(os.pwd / "docs" / "orders.mmd", dailyOrders.toMermaid)
```

## Config, injected once

Pipelines need database URLs, API keys, thresholds... and all types of other knobs you want to turn. Instead of parameter drilling them through every
function, etl4s lets a stage declares what it `.requires` and you `.provide` it once.

```scala
trait HasDb    { def jdbcUrl: String }
trait HasSlack { def webhook: String }

val readOrders = Extract[Unit, String].requires[HasDb] { cfg => _ =>
  runQuery(cfg.jdbcUrl, "SELECT id, amount, currency FROM orders")
}

val notifySlack = Load[List[Order], Unit].requires[HasSlack] { cfg => orders =>
  post(cfg.webhook, s"Loaded ${orders.size} orders")
}

val dailyOrders =
  (readOrders & fetchRates) ~> normalize ~> dedupe ~> (writeWarehouse & notifySlack)
```

`readOrders` needs a DB, `notifySlack` needs Slack. The middle stages carry nothing. 

etl4s infers the smallest config the pipeline needs, `HasDb & HasSlack`, and you provide it once:

```scala
case class AppConfig(jdbcUrl: String, webhook: String) extends HasDb with HasSlack

dailyOrders.provide(AppConfig("jdbc:pg://prod", "https://hooks.slack.com/...")).unsafeRun()

/** NOTE (Scala 2.x)
  * Use: `Node.requires[AppConfig, In, Out](cfg => in => out)` syntax
  */
```

[Read more about configuration](https://mattlianje.github.io/etl4s/config/)

## Type safety

**etl4s** won't let you chain together "blocks" that don't fit together:
```scala
 val fiveExtract: Extract[Unit, Int]        = Extract(5)
 val exclaim:     Transform[String, String] = Transform(_ + "!")

 fiveExtract ~> exclaim
```
The above will not compile with:
```shell
-- [E007] Type Mismatch Error: -------------------------------------------------
4 | fiveExtract ~> exclaim
  |                ^^^^^^^
  |                Found:    (exclaim : Transform[String, String])
  |                Required: Node[Int, Any]
```


## Parallelizing Tasks
**etl4s** has an elegant shorthand for grouping and parallelizing operations that share the same input type:
```scala
/* Simulate slow IO operations (e.g: DB calls, API requests) */

val e1 = Extract { Thread.sleep(100); 42 }
val e2 = Extract { Thread.sleep(100); "hello" }
val e3 = Extract { Thread.sleep(100); true }
```

Sequential run of e1, e2, and e3 **(~300ms total)**
```scala
val sequential: Extract[Unit, (Int, String, Boolean)] =
     e1 & e2 & e3
```

Parallel run of e1, e2, e3 on their own JVM threads with Scala Futures **(~100ms total, same result, 3X faster)**
```scala
val parallel: Extract[Unit, (Int, String, Boolean)] =
     e1 &> e2 &> e3
```

Mix sequential and parallel execution (first two parallel (~100ms), then third (~100ms)):
```scala
val mixed = (e1 &> e2) & e3
```

Full example of a parallel pipeline:
```scala
val consoleLoad: Load[String, Unit] = Load(println(_))
val dbLoad:      Load[String, Unit] = Load(x => println(s"DB Load: ${x}"))

val merge = Transform[(Int, String, Boolean), String] { case (i, s, b) =>
    s"$i-$s-$b"
  }

val pipeline =
  (e1 &> e2 &> e3) ~> merge ~> (consoleLoad &> dbLoad)
```

## Handling Failures

#### `withRetry`
Retry failed operations:
```scala
val callFlakyApi = Extract("response")
  .withRetry(maxAttempts = 3, initialDelayMs = 100)

callFlakyApi ~> parseResponse ~> saveResult
```

#### `onFailure`
Catch exceptions and recover:
```scala
val fetchUser = Extract[Unit, String](_ => throw new RuntimeException("Boom!"))
  .onFailure(e => s"Error: ${e.getMessage}")

fetchUser.unsafeRun(())  /* "Error: Boom!" */
```

## Conditional Branching

Route data through different pipelines with `If`, `ElseIf`, and `Else`:

```scala
val pipeline = extractUser
  .If(_.tier == "premium")      (validateUser ~> enrichUser ~> toPremiumOffer)
  .ElseIf(_.tier == "standard") (validateUser ~> toStandardOffer)
  .Else                         (toGuestNotice)
```

Read more [here](https://mattlianje.github.io/etl4s/branching/).

## Side Effects
Use `.tap()` for side effects without disrupting pipeline flow:

```scala
val listFiles  = Extract(List("a.txt", "b.txt"))
                   .tap(files => println(s"Processing: $files"))

val countFiles = Transform[List[String], Int](_.size)

listFiles ~> countFiles
```

Chain side effects with `>>`:
```scala
val logStart = Node { println("Starting...") }
val logEnd   = Node { println("Done!") }

val pipeline = logStart >> (listFiles ~> countFiles) >> logEnd
```

## Lineage

Track data lineage and visualize pipeline dependencies. Attach metadata to any Node or Reader then call `.toDot`, `.toJson` or `.toMermaid`
on individual instances or on Sequences:

```scala
val A = Node[String, String](identity)
  .lineage(
    name = "A",
    inputs = List("s1", "s2"),
    outputs = List("s3"), 
    schedule = "0 */2 * * *"
  )

val B = Node[String, String](identity)
  .lineage(
    name = "B",
    inputs = List("s3"),
    outputs = List("s4", "s5")
  )
```

Export lineage as JSON, DOT (Graphviz), or Mermaid diagrams:

```scala
Seq(A, B).toJson
Seq(A, B).toDot
```

<p align="center">
  <img src="pix/graphviz-example.svg" width="500">
</p>

```scala
Seq(A, B).toMermaid
```
```mermaid
graph LR
    classDef pipeline fill:#e1f5fe,stroke:#01579b,stroke-width:2px,color:#000
    classDef dataSource fill:#f3e5f5,stroke:#4a148c,stroke-width:2px,color:#000
    classDef cluster fill:#e8f5e8,stroke:#2e7d32,stroke-width:2px,color:#000

    A["A<br/>(0 */2 * * *)"]
    B["B"]
    s1(["s1"])
    s2(["s2"])
    s3(["s3"])
    s4(["s4"])
    s5(["s5"])

    s1 --> A
    s2 --> A
    A --> s3
    s3 --> B
    B --> s4
    B --> s5
    A -.-> B
    linkStyle 6 stroke:#ff6b35,stroke-width:2px

    class A pipeline
    class B pipeline
    class s1 dataSource
    class s2 dataSource
    class s3 dataSource
    class s4 dataSource
    class s5 dataSource
```

**etl4s** automatically infers dependencies by matching output -> input sources. Nodes don't need to be connected with `~>` for lineage tracking. Explicit dependencies via `upstreams` also supported.

## Examples

#### Chain two pipelines
Simple UNIX-pipe style chaining of two pipelines:
```scala
import etl4s._

val p1 = Node((i: Int) => i.toString)
val p2 = Node((s: String) => s + "!")

val p3 = p1 ~> p2
```

#### Complex chaining
Connect the output of two pipelines to a third:
```scala
import etl4s._

val namePipeline = Node("John Doe")
val agePipeline  = Node(30)
val toUpper      = Node[String, String](_.toUpperCase)
val consoleLoad  = Node[String, Unit](println(_))

val combined =
     (namePipeline & agePipeline) ~> toUpper ~> consoleLoad
```


## Inspiration
- Debasish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)
- Various Rich Hickey talks
