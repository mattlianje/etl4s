# Side Effects

## Observing with `.tap()`

Peek at values mid-pipeline without modifying them:

```scala
import etl4s._

val pipeline = Extract("hello world")
  .tap(x => println(s"Got: $x"))
  ~> Transform[String, Array[String]](_.split(" "))

pipeline.unsafeRun(())
// prints: Got: hello world
// returns: Array("hello", "world")
```

Chain taps at different stages:

```scala
val pipeline = fetchData
  .tap(files => println(s"Fetched ${files.size} files"))
  ~> processFiles
  .tap(count => println(s"Processed $count files"))
```

## Sequential Effects with `>>`

Run multiple nodes in order, same input to each. Only the last result is kept:

```scala
val logStart = Node[String, Unit](s => println(s"Start: $s"))
val logEnd   = Node[String, Unit](s => println(s"End: $s"))
val process  = Node[String, Int](_.length)

val pipeline = logStart >> logEnd >> process

pipeline.unsafeRun("hello")
// prints: Start: hello
// prints: End: hello
// returns: 5
```

Common for setup/teardown:

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

## Laziness

Side effects are **lazy** - nothing executes until `.unsafeRun()`. Build and compose freely without triggering effects.
