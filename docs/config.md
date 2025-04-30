
**etl4s** has a simple, powerful approach to dependency injection (aliased `Context`) based on `Reader` monads.
These just wrap your computations in the environment they need.

The "killer feature" is direct composition (via `~>`) of context-aware components without the usual flat-mapping boilerplate.
The most specific `Context` type needed is automatically resolved.



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
import etl4s._

/* Your config hierarchy ... */
trait BaseConfig { def appName: String }
trait DateConfig extends BaseConfig { def startDate: String }
trait DbConfig extends BaseConfig { def dbUrl: String }
trait FullConfig extends DateConfig with DbConfig

/* Components with different context requirements */
val baseComp = Context[BaseConfig, Extract[Unit, String]] { ctx =>
  Extract(_ => ctx.appName) 
}
val dateComp = Context[DateConfig, Transform[String, String]] { ctx =>
  Transform(s => s"${s}|${ctx.startDate}") 
}
val fullComp = Context[FullConfig, Transform[String, String]] { ctx =>
  Transform(s => s"${s}|${ctx.dbUrl}") 
}

/* Type resolution happens automatically */
val pipeline = baseComp ~> dateComp ~> fullComp

/* Configuration must satisfy the most specific requirements */
case class ETLConfig(appName: String, 
                     startDate: String,
                     dbUrl: String) extends FullConfig

val config = ETLConfig("MyApp", "2023-01-01", "localhost:5432")

val result = pipeline.provideContext(config).unsafeRun(())
```

