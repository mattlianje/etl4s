**etl4s** has one core building block:
```scala
Node[-In, +Out]
```
A Node wraps a lazily-evaluated function `In => Out`. Chain them with `~>` to build pipelines.

## Node types
To improve readability and express intent, **etl4s** defines four aliases: `Extract`, `Transform`, `Load` and `Pipeline`. All behave the same under the hood.

```scala
type Extract[-In, +Out]   = Node[In, Out]
type Transform[-In, +Out] = Node[In, Out]
type Load[-In, +Out]      = Node[In, Out]
type Pipeline[-In, +Out]  = Node[In, Out]
```

## Building pipelines
```scala
import etl4s._

val A = Extract("users.csv")
val B = Transform[String, Int](csv => csv.split("\n").length)
val C = Load[Int, Unit](count => println(s"Processed $count users"))

val pipeline = A ~> B ~> C

pipeline(())  // Processed 3 users
```

Create standalone nodes:
```scala
val toUpper = Transform[String, String](_.toUpperCase)
toUpper("hello")  // HELLO
```

## Running pipelines
Call like a function:
```scala
pipeline(())
```

Or be explicit:
```scala
pipeline.unsafeRun(())
```

**Error handling:**
```scala
val risky = Pipeline[String, Int](_.toInt)

risky.safeRun("42")    // Success(42)
risky.safeRun("oops")  // Failure(...)
```

**Execution details:**
```scala
val trace = pipeline.unsafeRunTrace(())
// trace.result, trace.logs, trace.timeElapsedMillis, trace.errors

val safeTrace = pipeline.safeRunTrace(())
// safeTrace.result is a Try[Out]
```

!!! note "Readers and config"
    **etl4s** also has a `Reader` type for dependency injection. Use `.requires` to turn any Node into a `Reader[Config, Node]`. The `~>` operator works between Nodes and Readers. See [Configuration](config.md).