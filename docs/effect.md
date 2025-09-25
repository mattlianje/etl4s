
# Side Effects with `tap`

The `tap` method lets you perform side effects without disrupting the data flow through your pipeline.
Perfect for logging, cleanup, debugging, or any operation that shouldn't affect your data.

```scala
import etl4s._

val fetchData    = Extract((_: Unit) => List("file1.txt", "file2.txt"))
val cleanup      = tap[List[String]] { files => 
  println(s"Processing ${files.size} files...")
  cleanupTempFiles(files)
}
val processFiles = Transform[List[String], Int](_.size)

val p = fetchData ~> cleanup ~> processFiles
```

## Using `Node.effect` for Unit Operations

For pure side effects that don't need the pipeline data, use `Node.effect`:

```scala
val clearCache = Node.effect { println("Clearing cache...") }
val purgeTemp  = Node.effect { cleanupTempFiles() }

val p = clearCache >> purgeTemp >> fetchData ~> processFiles
```
