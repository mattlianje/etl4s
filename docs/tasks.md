# Parallel Execution

**etl4s** has an elegant shorthand for grouping and parallelizing operations that
share the same input type:

```scala
import etl4s._

/* Simulate slow IO operations (e.g: DB calls, API requests) */

val e1 = Extract { Thread.sleep(100); 42 }
val e2 = Extract { Thread.sleep(100); "hello" }
val e3 = Extract { Thread.sleep(100); true }
```

`&` fans the same input out to all three, sequentially (~300ms):
```scala
val sequential = e1 & e2 & e3

sequential.unsafeRun(())
```
You will get:
```
(42, hello, true)
```

`&>` is the concurrent counterpart - same result (~100ms), running the branches concurrently when compiled to an effect like `Future`:
```scala
val parallel = e1 &> e2 &> e3

parallel.compile[Future].unsafeRun(())
```
You will get:
```
(42, hello, true)
```

Mix the two - `e1` and `e2` concurrent, then `e3`:
```scala
val mixed = (e1 &> e2) & e3

mixed.compile[Future].unsafeRun(())
```
You will get:
```
((42, hello), true)
```

Full example of a parallel pipeline:
```scala
val consoleLoad: Load[String, Unit] = Load(println(_))
val dbLoad:      Load[String, Unit] = Load(x => println(s"DB Load: ${x}"))

val merge = Transform[(Int, String, Boolean), String] { case (i, s, b) =>
    s"$i-$s-$b"
  }

val pipeline =
  (e1 &> e2 &> e3) ~> merge ~> (consoleLoad &> dbLoad)

pipeline.compile[Future].unsafeRun(())
```

Concurrency only kicks in under a concurrent effect - see
[Effect polymorphism](effect-polymorphism.md). For per-element parallelism over a
collection, see `eachPar` in [Batch collections](batch.md).
