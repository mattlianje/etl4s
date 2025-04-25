# etl4s

**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency library for writing type-safe, beautiful ✨🍰  data flows in functional Scala.

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
val cleanup   = Pipeline[Unit, Unit](_ => println("Cleanup complete"))

/* Group tasks with &, Connect with ~>, Sequence with >> */
val pipeline =
     (getUser & getOrder) ~> process ~> (saveDb & sendEmail) >> cleanup

/* Run at end of World */
pipeline.unsafeRun(())
```
