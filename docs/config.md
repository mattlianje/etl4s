
**etl4s** makes config-aware pipelines dead simple:
You just write `.requires[Config] { cfg => in => ... }` and later `.provide(config)`

## Declaring a config-aware step
```scala
import etl4s._

/* A config object */
case class ApiConfig(url: String, key: String)

val fetch = Extract("user123")

/* A config-aware transform step */
val enrich = Transform[String, String].requires[ApiConfig] { cfg => user =>
  s"Processed with ${cfg.key}: $user"
}

val pipeline = fetch ~> enrich

val myApiConfig = ApiConfig("https://api.com", "SECRET")

/* Provide config and run */
val result = pipeline.provide(myApiConfig).unsafeRun(())
```

## Auto-propagated config via traits
Break your config into capabilities. **etl4s** will automatically infer the combined config
your pipeline needs.
```scala
trait HasBase       { def appName: String }
trait HasDateRange  extends HasBase { def start: String; def end: String }
trait HasDb         extends HasDateRange { def dbUrl: String }
```
Next, you can build a library of re-usable steps:
```scala
object Steps {
  val log = Transform[String, String].requires[HasBase] { cfg => s =>
       s"[${cfg.appName}] $s"
  }

  val tagDate = Transform[String, String].requires[HasDateRange] { cfg => s =>
       s"$s (${cfg.start} to ${cfg.end})"
  }

  val save = Load[String, Boolean].requires[HasDb] { cfg => s =>
       println(s"Saving to ${cfg.dbUrl}: $s"); true
  }
}
```

And run with a config:
```scala
import Steps._

case class MyJobConfig(
  appName: String,
  start: String,
  end: String,
  dbUrl: String
) extends HasDb

val randomConfig = MyJobConfig("etl4s", "2023-01-01", "2023-01-31", "jdbc:pg")

val fullPipeline = Extract("hello") ~> log ~> tagDate ~> save

fullPipeline.provide(randomConfig).unsafeRun(())
```

## ✍️ Optional: Use Etl4sContext[Cfg]
If you're writing a full library of config-aware steps with the same Cfg, extend `Etl4sContext`:
```scala
object MyPipeline extends Etl4sContext[ApiConfig] {
  val step1 = extractWithContext { cfg => _ => fetchUser(cfg.key) }
  val step2 = transformWithContext { cfg => user => enrichUser(user) }
}
```
It saves you from repeated `.requires[ApiConfig]`

## Note for Scala 2.x
In Scala 2, type inference is a bit stricter. Use:
```scala
Transform.requires[Cfg, In, Out](cfg => in => ...)
```
Instead of just:
```scala
Transform.requires[Cfg] { cfg => in => ... }
```

