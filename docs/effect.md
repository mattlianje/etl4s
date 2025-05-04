
The `effect` method lets you execute operations that don't need inputs or return meaningful outputs, 
like printing to stdout or gluing nodes with unrelated types.

```scala
import etl4s._

val fetchData    = Extract(() => List("file1.txt", "file2.txt"))
val processFiles = Transform[List[String], Int](_.size)

val p = effect { println("clear dir ...") } >>
        effect { println("purge caches ...") } >>
        fetchData ~>
	processFiles
```
