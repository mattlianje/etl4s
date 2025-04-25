
### Chain two pipelines
Simple UNIX-pipe style chaining of two pipelines:
```scala
import etl4s.*

val p1 = Pipeline((i: Int) => i.toString)
val p2 = Pipeline((s: String) => s + "!")

val p3: Pipeline[Int, String] = p1 ~> p2
```

### Complex chaining
Connect the output of two pipelines to a third:
```scala
import etl4s.*

val namePipeline = Pipeline((_: Unit) => "John Doe")
val agePipeline = Pipeline((_: Unit) => 30)

val combined: Pipeline[Unit, Unit] =
  for {
    name <- namePipeline
    age <- agePipeline
    _ <- Extract(s"$name | $age") ~> Transform(_.toUpperCase) ~> Load(println(_))
  } yield ()
```
