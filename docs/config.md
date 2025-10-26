
# Configuration

Make your pipelines configurable and reusable. Declare what each step needs with `.requires`, then provide config once. The required context is automatically inferred.

```scala
import etl4s._

case class Cfg(key: String)

val A = Extract("data")
val B = Transform[String, String].requires[Cfg] { cfg => data =>
  s"${cfg.key}: $data"
}

val pipeline = A ~> B

pipeline.provide(Cfg("secret")).unsafeRun(())  // "secret: data"
```

## Config Propagation

Build modular configs with traits. **etl4s** automatically infers what your pipeline needs:

```scala
trait HasDb { def dbUrl: String }
trait HasAuth { def apiKey: String }

val A = Load[String, Unit].requires[HasDb] { cfg => data =>
  println(s"Saving to ${cfg.dbUrl}: $data")
}

val B = Extract[Unit, String].requires[HasAuth] { cfg => _ =>
  s"Fetched with ${cfg.apiKey}"
}

val C = Transform[String, String](_.toUpperCase)

// Combined config
case class AppConfig(dbUrl: String, apiKey: String) extends HasDb with HasAuth

val pipeline = B ~> C ~> A

// Config flows to all steps automatically
pipeline.provide(AppConfig("jdbc:pg", "secret-key")).unsafeRun(())
```

## Context

`Context[T]` is a trait that provides organized factory methods for config-driven operations. For larger applications, extend it to keep your context-aware pipelines organized:

```scala
case class DbConfig(url: String, timeout: Int)

object DataPipeline extends Context[DbConfig] {
  
  val fetch = Context.Extract[Unit, String] { cfg => _ =>
    s"Connected to ${cfg.url} with timeout ${cfg.timeout}s"
  }
  
  val save = Context.Load[String, Unit] { cfg => data =>
    println(s"Saving to ${cfg.url}: $data")
  }
  
  val pipeline = fetch ~> save
}

// Provide config once
DataPipeline.pipeline.provide(DbConfig("jdbc:pg", 5000)).unsafeRun(())
```

## Scala 2.x Note

Use explicit types for better inference:

```scala
// Scala 2.x
Transform.requires[Config, String, String] { cfg => input => 
  cfg.key + input
}

// Scala 3 (preferred)
Transform[String, String].requires[Config] { cfg => input => 
  cfg.key + input
}
```

