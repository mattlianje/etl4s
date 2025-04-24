# etl4s

**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency library for writing type-safe data flows in functional Scala.

## Get started

**etl4s** is on MavenCentral and cross-built for Scala, 2.12, 2.13, 3.x:
```scala
"xyz.matthieucourt" %% "etl4s" % "1.2.0"
```

Try it in your repl (with [scala-cli](https://scala-cli.virtuslab.org/)):
```bash
scala-cli repl --scala 3 --dep xyz.matthieucourt:etl4s_3:1.2.0
```

All you need:
```scala
import etl4s._
```

## Code Example
```scala
import etl4s.*

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
pipeline.unsafeRun(())
```
