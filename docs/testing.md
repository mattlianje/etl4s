
Use **etl4s** with the testing framework of your choice

Run nodes like normal functions
```scala
import etl4s._

val times5: Transform[Int, Int] = Transform(_ * 5)

times5(5)
```

You will get:
```
25
```

Run pipelines with `unsafeRun` or `safeRun`:

```scala
import etl4s._

val plus2:  Transform[Int, Int] = Transform(_ + 2)
val times5: Transform[Int, Int] = Transform(_ * 5)

val p: Pipeline[Int, Int] = plus2 ~> times5

p.unsafeRun(2)
```
Gives
```
20
```
However, if you use `safeRun` as below
```scala
p.safeRun(2)
```
You will get a response wrapped in a `scala.util.Try`
```
Success(20)
```

