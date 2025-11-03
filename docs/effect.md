
# Side Effects

**etl4s** makes it easy to work with side effects in your pipelines.

## Using `.tap()` for Inline Side Effects

The `.tap()` method lets you perform side effects without disrupting the data flow through your pipeline.
Perfect for logging, cleanup, debugging, or any operation that shouldn't affect your data.

```scala
import etl4s._

val fetchData    = Extract[List[String]]{ List("file1.txt", "file2.txt") }
val processFiles = Transform[List[String], Int](_.size)

val p = fetchData
  .tap(files => println(s"Processing ${files.size} files..."))
  ~> processFiles
```

## Wrapping Side Effects in Nodes

For standalone side effects, just wrap them in `Node { ... }`:

```scala
val clearCache = Node { println("Clearing cache...") }
val purgeTemp  = Node { println("More cleanup..") }

// Chain side effects with >>
val p = clearCache >> purgeTemp >> fetchData ~> processFiles
```

Side effects are **lazy** - they only execute when you call `.unsafeRun()`.
