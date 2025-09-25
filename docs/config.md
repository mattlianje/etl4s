
# Config-Driven Pipelines

Make your pipelines configurable and reusable. Declare what each step needs, then provide config once.

```scala
import etl4s._

case class ApiConfig(url: String, key: String)

val extract = Extract("user123")
val enrich = Transform[String, String].requires[ApiConfig] { cfg => user =>
  s"${cfg.key}: $user"
}

val pipeline = extract ~> enrich

// Provide config and run
pipeline.provide(ApiConfig("api.com", "SECRET")).unsafeRun(())
```

## Config Inheritance & Propagation

Build modular configs with traits. **etl4s** automatically infers what your pipeline needs and propagates config through the entire pipeline:

```scala
trait HasDb { def dbUrl: String }
trait HasAuth { def apiKey: String }

val saveData = Load[String, Unit].requires[HasDb] { cfg => data =>
  println(s"Saving to ${cfg.dbUrl}")
}

val fetchUser = Extract[String, User].requires[HasAuth] { cfg => id =>
  fetchFromApi(cfg.apiKey, id)
}

// Combined config
case class AppConfig(dbUrl: String, apiKey: String) extends HasDb with HasAuth

val pipeline = fetchUser ~> process ~> saveData
// Config flows to all steps that need it automatically
pipeline.provide(AppConfig("jdbc:pg", "secret")).unsafeRun("user123")
```

## Scala 2.x Note

Use explicit types for better inference:

```scala
// Scala 2.x
Transform.requires[Config, String, String] { cfg => input => 
  process(cfg, input)
}

// Scala 3 (preferred)
Transform[String, String].requires[Config] { cfg => input => 
  process(cfg, input)
}
```

