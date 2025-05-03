
The `tap` method allows you to observe values flowing through your pipeline without modifying them. 
This is useful for logging, debugging, or collecting metrics.

```scala
import etl4s._

val sayHello   = Extract("hello world")
val splitWords = Transform[String, Array[String]](_.split(" "))

val pipeline = sayHello ~> 
               tap((x: String) => println(s"Processing: $x")) ~>
               splitWords

val result = pipeline.unsafeRun(())
```
This will return `Array("hello", "world")` and also prints to stdout `Processing: hello world`
