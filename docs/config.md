
**etl4s** encourages `Reader` (aliased `Context` in **etl4s**)  monad based dependency injection.

Wrap your blocks in the `Context` they need, and chain together blocks propagating the most
specific subtype.

```scala
import etl4s._

case class ApiConfig(url: String, key: String)
val config = ApiConfig("https://api.example.com", "secret-key")

/* Create context-aware components */
val fetchData = Context[ApiConfig, Extract[String, String]] { ctx =>
  Extract(id => s"Fetched $id from ${ctx.url}")
}

val processData = Context[ApiConfig, Transform[String, String]] { ctx =>
  Transform(data => s"Processed with key ${ctx.key}: $data")
}

/* Connect them directly with ~> */
val pipeline = fetchData ~> processData

/* Provide config at runtime */
val result = pipeline.provideContext(config).unsafeRun("user123")
```

For cleaner code and access to the `WithContext` aliases use the `Etl4sContext` trait:
```scala
object DummyService extends Etl4sContext[ApiConfig] {
  val extract: ExtractWithContext[String, String] = Context { config => 
    Extract(id => s"Fetched $id from ${config.url}")
  }
  
  val transform: TransformWithContext[String, Int] = Context { config =>
    Transform(s => s.length)
  }
}

import DummyService._

val pipeline = extract ~> transform
```

### Aliases

With `Etl4sContext[T]`:

| Standard Type | etl4s Alias |
|:--------------|:------------|
| `Reader[T, Extract[A, B]]` | `ExtractWithContext[A, B]` |
| `Reader[T, Transform[A, B]]` | `TransformWithContext[A, B]` |
| `Reader[T, Load[A, B]]` | `LoadWithContext[A, B]` |
| `Reader[T, Pipeline[A, B]]` | `PipelineWithContext[A, B]` |

### Environment Propagation

**etl4s** automatically resolves the most specific configuration type needed when connecting components.

```scala
/* Define minimal config hierarchy */
trait BaseConfig { def appName: String }
trait SpecificConfig extends BaseConfig { def apiKey: String }

/* Components with different requirements */
val comp1 = Context[BaseConfig, Extract[Unit, String]] { cfg =>
  Extract(_ => cfg.appName)
}

val comp2 = Context[SpecificConfig, Transform[String, String]] { cfg =>
  Transform(s => s"$s: ${cfg.apiKey}")
}

/* Magic happens here - type resolved to SpecificConfig */
val pipeline = comp1 ~> comp2

/* Must provide the more specific config */
val config = new SpecificConfig {
  val appName = "MyApp"
  val apiKey = "secret"
}

val result = pipeline.provideContext(config).unsafeRun(())
// Result: "MyApp: secret"
```

