
The `tap` method allows you to observe values flowing through your pipeline without modifying them. 
This is useful for logging, debugging, or collecting metrics.

```scala
import etl4s._

val sayHello   = Extract("hello world")
val splitWords = Transform[String, Array[String]](_.split(" "))
val toUpper    = Transform[Array[String], Array[String]](_.map(_.toUpperCase))

val pipeline = sayHello ~> 
               splitWords ~>
               tap(words => println(s"Processing ${words.length} words")) ~> 
               toUpper

val result = pipeline.unsafeRun(())
// Result: Array("HELLO", "WORLD")
// ...but also prints: "Processing 2 words"
```
