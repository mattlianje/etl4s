# etl4s
**Easy, whiteboard-style data**

A lightweight, type-safe library for building ETL pipelines using functional programming principles.

## Features
- White-board style ETL, snapped together from your functions
- Monadic composition for sequencing pipelines
- Drop **Etl4s.scala** into any Scala 2.12+ project like a header file
- Type-safe transformations w/ compile-time checking
- Concurrenct execution on-top of Scala Futures
- Built in retry-mechanism

## Get started
```scala
/* Define your pipeline components */
val fiveExtract = Extract(5)
val times2: Transform[Int, Int] = Transform(_ * 2)
val exclaim: Transform[Int, String] = Transform(_.toString + "!")
val consoleLoad: Load[String, Unit] = Load(println(_))

/* Compose them into a pipeline */
val pipeline = 
     fiveExtract ~> times2 ~> exclaim ~> consoleLoad

/* Run your pipeline at the end of the World */
val result = pipeline.unsafeRun()
// result: 10!
```

## Core Concepts
**Etl4s** has 3 building blocks and 2 main operators
