# Configuration

When writing pipelines, you often need to:

- Pass database URLs, API keys, thresholds to various stages
- Avoid threading config through every function signature
- Keep stages testable by swapping config at the edge

`.requires` declares what a node needs. `.provide` supplies it once at the top. Config flows through automatically - it's just a Reader monad (`Config => Node[In, Out]`) with some syntax.

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

Build modular configs with traits. etl4s infers what your pipeline needs:

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

case class AppConfig(dbUrl: String, apiKey: String) extends HasDb with HasAuth

val pipeline = B ~> C ~> A

pipeline.provide(AppConfig("jdbc:pg", "secret-key")).unsafeRun(())
```

## Context

`Context[T]` organizes config-driven nodes into modules:

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

DataPipeline.pipeline.provide(DbConfig("jdbc:pg", 5000)).unsafeRun(())
```

!!! note "Scala 2"
    Use explicit types for better inference:
    ```scala
    Transform.requires[Config, String, String] { cfg => input =>
      cfg.key + input
    }
    ```
    In Scala 3, the preferred syntax is:
    ```scala
    Transform[String, String].requires[Config] { cfg => input =>
      cfg.key + input
    }
    ```
