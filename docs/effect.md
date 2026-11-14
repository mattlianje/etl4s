# Side Effects

## Peek at values with `.tap()`

Look at a value mid-pipeline without changing it:

```scala
import etl4s._

val greeting = Node("hello world")
val split    = Node[String, Array[String]](_.split(" "))

val pipeline = greeting
  .tap(x => println(s"Got: $x"))
  ~> split

pipeline.unsafeRun(())
```
Prints:
```
Got: hello world
```
and returns `Array("hello", "world")`.

You can tap at several stages:

```scala
val pipeline = fetchData
  .tap(files => println(s"Fetched ${files.size} files"))
  ~> processFiles
  .tap(count => println(s"Processed $count files"))
```

## Run steps in order with `>>`

Runs several nodes in order, each with the same input, and keeps only the last result:

```scala
val logStart = Node[String, Unit](s => println(s"Start: $s"))
val logEnd   = Node[String, Unit](s => println(s"End: $s"))
val process  = Node[String, Int](_.length)

val pipeline = logStart >> logEnd >> process

pipeline.unsafeRun("hello")
```
Prints:
```
Start: hello
End: hello
```
and returns `5`.

Useful for setup and teardown:

```scala
val clearCache = Node { println("Clearing cache...") }
val warmCache  = Node { println("Warming cache...") }

val pipeline = clearCache >> warmCache >> mainPipeline
```

Or audit logging:

```scala
val auditStart = Node[Request, Unit](r => log(s"Started ${r.id}"))
val auditEnd   = Node[Request, Unit](r => log(s"Finished ${r.id}"))

val pipeline = auditStart >> processRequest >> auditEnd
```

## Build now, run later

Nothing runs until you call `.unsafeRun()`, so you can build and compose pipelines without triggering anything.
