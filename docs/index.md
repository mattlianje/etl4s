# etl4s

A lightweight, zero-dependency library for writing type-safe, whiteboard-style ✨🍰 pipelines in functional Scala

_**Why?**_

- Chaotic, framework/infra-coupled ETL codebases that grow without an imposed discipline drive data-orgs to their knees
- **etl4s** was made for engineers who value simple, composable and config-driven dataflows

## Quickstart
```scala
import etl4s._

/* Define components */
val getUser  = Extract("john_doe") ~> Transform(_.toUpperCase)
val getOrder = Extract("2 items")
val process  = Transform[(String, String), String] { case (user, order) => 
  s"$user ordered $order" 
}
val saveDb    = Load[String, String](s => { println(s"DB: $s"); s })
val sendEmail = Load[String, Unit](s => println(s"Email: $s"))
val cleanup   = Pipeline[Unit, Unit]((_: Unit) => println("Cleanup complete"))

/* Group tasks with &, Connect with ~>, Sequence with >> */
val pipeline =
     (getUser & getOrder) ~> process ~> (saveDb & sendEmail) >> cleanup

/* Run at end of World */
pipeline.unsafeRun(())
```

## Type safety
**etl4s** won't let you chain together "blocks" that don't fit together:
```scala
 val fiveExtract: Extract[Unit, Int]        = Extract(5)
 val exclaim:     Transform[String, String] = Transform(_ + "!")

 fiveExtract ~> exclaim
```
The above will not compile with:
```shell
-- [E007] Type Mismatch Error: -------------------------------------------------
4 | fiveExtract ~> exclaim
  |                ^^^^^^^
  |                Found:    (exclaim : Transform[String, String])
  |                Required: Node[Int, Any]
```

