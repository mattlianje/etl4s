
### Chain two pipelines
```scala
import etl4s._

val A = Pipeline((i: Int) => i.toString)
val B = Pipeline((s: String) => s + "!")

val C = A ~> B  // Int => String
```

### Complex chaining
```scala
import etl4s._

val A = Pipeline("data")
val B = Pipeline(42)
val C = Transform[String, String](_.toUpperCase)
val D = Load[String, Unit](println)

val pipeline =
  for {
    str <- A
    num <- B
    _ <- Extract(s"$str-$num") ~> C ~> D
  } yield ()
```
