# Parallel Execution

Swap `&` for `&>` and nodes that share an input run in parallel:

```scala
import etl4s._

/* Simulate slow IO operations (e.g: DB calls, API requests) */

val e1 = Extract { Thread.sleep(100); 42 }
val e2 = Extract { Thread.sleep(100); "hello" }
val e3 = Extract { Thread.sleep(100); true }
```

Run them one after the other (~300ms total):
```scala
val sequential: Extract[Unit, (Int, String, Boolean)] =
  e1 & e2 & e3
```

Run them in parallel on their own threads (~100ms total, same result, 3X faster):
```scala
val parallel: Extract[Unit, (Int, String, Boolean)] =
  e1 &> e2 &> e3
```

Or mix the two. Here the first two run in parallel (~100ms), then the third (~100ms):
```scala
val mixed = (e1 &> e2) & e3
```

A full parallel pipeline:
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

This only runs in parallel under a concurrent effect like `Future` (see
[Effect polymorphism](effect-polymorphism.md)). To process the elements of a
collection in parallel, see `eachPar` in [Batch collections](batch.md).
