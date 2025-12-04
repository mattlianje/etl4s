# Side Effects

**etl4s** makes it easy to work with side effects in your pipelines.

## Observing Data with `.tap()`

The `.tap()` method lets you observe values flowing through your pipeline without modifying them. Perfect for logging, debugging, metrics, or any operation where you want to "peek" at data mid-flow.

```scala
import etl4s._

val sayHello   = Extract("hello world")
val splitWords = Transform[String, Array[String]](_.split(" "))

val pipeline = sayHello
  .tap(x => println(s"Processing: $x"))
  ~> splitWords

pipeline.unsafeRun(())
// prints: Processing: hello world
// returns: Array("hello", "world")
```

Chain multiple taps for observability at different stages:

```scala
val fetchData    = Extract(List("file1.txt", "file2.txt"))
val processFiles = Transform[List[String], Int](_.size)

val p = fetchData
  .tap(files => println(s"Fetched ${files.size} files"))
  ~> processFiles
  .tap(count => println(s"Processed $count files"))
```

## Standalone Side Effects

For side effects that don't transform data, wrap them in `Node { ... }`:

```scala
val clearCache = Node { println("Clearing cache...") }
val purgeTemp  = Node { println("Purging temp files...") }

// Chain side effects with >>
val p = clearCache >> purgeTemp >> fetchData ~> processFiles
```

The `>>` operator runs nodes in sequence, passing the same input to each.

## Laziness

Side effects are **lazy** - nothing executes until you call `.unsafeRun()`. This lets you build and compose pipelines without triggering effects prematurely.
