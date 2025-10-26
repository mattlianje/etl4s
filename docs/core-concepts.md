
At the heart of **etl4s** is a single abstraction:
```scala
Node[-In, +Out]
```
A Node is just a lazy, typed function `In => Out` that can be chained into pipelines using `~>`. That's it.

## Node types
For clarity and intent, **etl4s** provides 4 nodes aliases:
```scala
type Extract[-In, +Out]   = Node[In, Out]
type Transform[-In, +Out] = Node[In, Out]
type Load[-In, +Out]      = Node[In, Out]
type Pipeline[-In, +Out]  = Node[In, Out]
```
They all behave identically under the hood.

## Quick examples
```scala
import etl4s._

// A basic extract node
val extract: Extract[Unit, String] = Extract("hello")

// A transform node from String to Int
val getStringLen = Transform[String, Int](_.length)

println(extract(()))        // hello
println(getStringLen("hi")) // 2
```
You can wrap any Function1:
```scala
val toStr = Extract[Int, String](_.toString)
```

## Building pipelines
Compose nodes with `~>`:
```scala
val A = Extract("hello")
val B = Transform[String, Int](_.length)
val C = Load[Int, String](n => s"Length: $n")

val pipeline = A ~> B ~> C
```
Or define a pipeline from any Function1:
```scala
val shout = Pipeline[String, String](_.toUpperCase)
```

## Executing pipelines
### 1) Call them like functions
All pipelines are just values of type `In => Out`, so you can run them like this:
```scala
pipeline(())        /* => "Length: 5" */
shout("hi")         /* => "HI" */
```

### 2) Use `.unsafeRun(...)`
To run with error surfacing (trace information collected internally):
```scala
pipeline.unsafeRun(())
```

### 3) Use `.safeRun(...)`
To catch exceptions (trace information collected internally):
```scala
val risky = Pipeline[String, Int](_.toInt)
val result = risky.safeRun("oops")  // => Failure(...)
```

### 4) Use traced execution
To get full execution details including logs, timing, and validation errors:
```scala
val trace = pipeline.unsafeRunTrace(())
// trace.result, trace.logs, trace.timeElapsedMillis, trace.errors

val safeTrace = pipeline.safeRunTrace(())  
// safeTrace.result is a Try[B]
```

### 5) Run and measure time
Run your pipeline:
```scala
val slow = Node[Unit, Unit](_ => Thread.sleep(100))
val (_, elapsedMs) = slow.unsafeRunTimedMillis(())
```