<p align="center">
  <img src="pix/etl4s.png" width="700">
</p>

# <img src="pix/etl4s-logo.png" width="50"> etl4s
**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency library for writing type-safe, beautiful ✨🍰  data flows in functional Scala. 
Battle-tested at [Instacart](https://www.instacart.com/). Part of [d4](https://github.com/mattlianje/d4)

<div align="center">
  <a href="https://www.bitcomplete.io/"><img src="pix/bc-logo.png" width="48"></a>
  <sub><i> Sponsored by <a href="https://www.bitcomplete.io/">Bit Complete</a></i></sub>
</div>

## Features
- Declarative, typed pipeline endpoints
- Use **Etl4s.scala** like a header file
- Type-safe, compile-time checked
- [Config-driven](#configuration) by design
- Easy, monadic composition of pipelines
- Built-in retry/failure handling
- Automatic [trace collection](#accessing-runtime-state-with-trace)

## Installation

**etl4s** is on MavenCentral and cross-built for Scala, 2.12, 2.13, 3.x
```scala
"xyz.matthieucourt" %% "etl4s" % "1.4.1"
```
Or try in REPL:
```bash
scala-cli repl --scala 3 --dep xyz.matthieucourt:etl4s_3:1.4.1
```

All you need:
```scala
import etl4s._
```

## Quick Example
```scala
import etl4s._

/* Define components */
val getUser  = Extract("John Doe")
val getOrder = Extract("2 items")
val process  = Transform[(String, String), String] { case (user, order) => 
  s"$user ordered $order" 
}
val saveDb    = Load[String, String](s => { println(s"DB: $s"); s })
val sendEmail = Load[String, Unit](s => println(s"Email: $s"))

/* Group tasks with &, Connect with ~> */
val pipeline =
     (getUser & getOrder) ~> process ~> (saveDb & sendEmail)
pipeline.unsafeRun(())
```

## Documentation 
[Full Documentation](https://mattlianje.github.io/etl4s/) - Detailed guides, API references, and examples

## Core Concepts
**etl4s** has one core building block:
```scala
Node[-In, +Out]
```
A Node wraps a lazily-evaluated function `In => Out`. Chain them with `~>` to build pipelines.

To improve readability and express intent, **etl4s** defines four aliases: `Extract`, `Transform`, `Load` and `Pipeline`.
All behave the same under the hood.

Drop in any function like:
```scala
val step = Transform[String, Int](_.length)
```

You can run nodes like plain functions:
```scala
val len: Int = step("HELLO") // 5
```
Or explicitly:
- `.unsafeRun(input)` - runs and throws on failure (trace collected internally)
- `.safeRun(input)` - returns a Try (trace collected internally) 
- `.unsafeRunTrace(input)` - returns Trace with logs, timeElapsedMillis, validation errors
- `.safeRunTrace(input)` - returns Trace with Try result safely

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

## Of note...
- Ultimately - these nodes and pipelines are just reifications of functions and values (with a few niceties like built in retries, failure handling, concurrency-shorthand, and Future based parallelism).
- Chaotic, framework/infra-coupled ETL codebases that grow without an imposed discipline drive dev-teams and data-orgs to their knees.
- **etl4s** is a little DSL to enforce discipline, type-safety and re-use of pure functions - 
and see [functional ETL](https://maximebeauchemin.medium.com/functional-data-engineering-a-modern-paradigm-for-batch-data-processing-2327ec32c42a) for what it is... and could be.

## Operators

etl4s uses a few simple operators to build pipelines:

| Operator | Name | Description | Example |
|----------|------|-------------|---------|
| `~>` | Connect | Chains operations in sequence | `e1 ~> t1 ~> l1` |
| `&` | Combine | Group sequential operations with same input | `t1 & t2` |
| `&>` | Parallel | Group concurrent operations with same input | `t1 &> t2` |
| `>>` | Sequence | Runs pipelines in order (ignoring previous output) | `p1 >> p2` |


## Handling Failures
**etl4s** comes with 2 methods you can use (on a `Node` or `Pipeline`) to handle failures out of the box:

#### `withRetry`
Give retry capability using the built-in `withRetry`:
```scala
import etl4s._

var attempts = 0

val riskyTransformWithRetry = Transform[Int, String] {
    n =>
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"Attempt $attempts failed")
      else s"Success after $attempts attempts"
}.withRetry(maxAttempts = 3, initialDelayMs = 10)

val pipeline = Extract(42) ~> riskyTransformWithRetry
pipeline.unsafeRun(())
```
This prints:
```
Success after 3 attempts
```

#### `onFailure`
Catch exceptions and perform some action:
```scala
import etl4s._

val riskyExtract =
    Extract[Unit, String](_ => throw new RuntimeException("Boom!"))

val safeExtract = riskyExtract
                    .onFailure(e => s"Failed with: ${e.getMessage} ... firing missile")
val consoleLoad: Load[String, Unit] = Load(println(_))

val pipeline = safeExtract ~> consoleLoad
pipeline.unsafeRun(())
``` 
This prints:
```
Failed with: Boom! ... firing missile
```

## Side Effects with `tap`
The `tap` method performs side effects without disrupting pipeline flow:

```scala
import etl4s._

val fetchData = Extract(_ => List("file1.txt", "file2.txt"))
val cleanup   = tap[List[String]] { files => cleanupTempFiles(files) }
val process   = Transform[List[String], Int](_.size)

val pipeline = fetchData ~> cleanup ~> process
```

## Accessing runtime state with `Trace` 
Nodes can access and update their runtime state - recording messages, reporting errors, and checking execution timing. All state is automatically shared across your entire pipeline. Read more [here](https://mattlianje.github.io/etl4s/trace/)

```scala
val upstream = Transform[String, Int] { input =>
  if (input.isEmpty) Trace.error("Empty input")
  input.length
}

val downstream = Transform[Int, String] { value =>
  if (Trace.hasErrors) "FALLBACK" else s"Length: $value"  
}

val p = upstream ~> downstream

p.unsafeRun("")        /* "FALLBACK" */
p.unsafeRunTrace("")   /* Full trace with execution details */
```

```
Trace(
  result = "FALLBACK", 
  errors = List("Empty input"),
  logs = List(),
  timeElapsedMillis = 2L
)
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
val sequential: Extract[Unit, ((Int, String), Boolean)] =
     e1 & e2 & e3
```

Parallel run of e1, e2, e3 on their own JVM threads with Scala Futures **(~100ms total, same result, 3X faster)**
```scala
import scala.concurrent.ExecutionContext.Implicits.global

val parallel: Extract[Unit, ((Int, String), Boolean)] =
     e1 &> e2 &> e3
```
Use the built-in zip method to flatten unwieldly nested tuples:
```scala
val clean: Extract[Unit, (Int, String, Boolean)] =
     (e1 & e2 & e3).zip
```
Mix sequential and parallel execution (First two parallel (~100ms), then third (~100ms)):
```scala
val mixed = (e1 &> e2) & e3
```

Full example of a parallel pipeline:
```scala
val consoleLoad: Load[String, Unit] = Load(println(_))
val dbLoad:      Load[String, Unit] = Load(x => println(s"DB Load: ${x}"))

val merge = Transform[(Int, String, Boolean), String] { t => 
    val (i, s, b) = t
    s"$i-$s-$b"
  }

val pipeline =
  (e1 &> e2 &> e3).zip ~> merge ~> (consoleLoad &> dbLoad)
```

## Configuration

Some steps need config. Some don’t.
Just declare what each step `.requires`, then `.provide` it later.

```scala
Node[In, Out].requires[Config](cfg => in => out)
// Scala 2.x:
// Node.requires[Config, In, Out](cfg => in => out)
```
Like this, every Node step can declare the exact config it needs (just Reader monads under the hood):
```scala
import etl4s._

case class ApiConfig(url: String, key: String)

val fetchData  = Extract("user123")
val enrichData = Transform[String, String].requires[ApiConfig] { cfg => user =>
  s"Processed with ${cfg.key}: $user"
}

val pipeline = fetchData ~> enrichData
```
**etl4s** automatically infers the smallest shared config needed for your whole pipeline.
Just `.provide` once.


## Examples

#### Chain two pipelines
Simple UNIX-pipe style chaining of two pipelines:
```scala
import etl4s._

val p1 = Pipeline((i: Int) => i.toString)
val p2 = Pipeline((s: String) => s + "!")

val p3 = p1 ~> p2
```

#### Complex chaining
Connect the output of two pipelines to a third:
```scala
import etl4s._

val namePipeline = Pipeline("John Doe")
val agePipeline  = Pipeline(30)
val toUpper      = Transform[String, String](_.toUpperCase)
val consoleLoad  = Load[String, Unit](println(_))

val combined =
  for {
    name <- namePipeline
    age <- agePipeline
    _ <- Extract(s"$name | $age") ~> toUpper ~> consoleLoad
  } yield ()
```

## Real-world examples
**etl4s** works great with anything:
- Spark / Flink / Beam
- ETL / Streaming
- Distributed Systems
- Local scripts
- Big Data workflows
- Web-server dataflows


## Inspiration
- Debasish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)
- Various Rich Hickey talks


