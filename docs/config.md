
**etl4s** has a simple but powerful approach to dependency injection, using a Reader-based wrapper under the hood, but all you write is `.requires` and `.provide`

All you write is:
```scala
.requires[Config, Input, Output](cfg => input => ...)
```

Like this, every pipeline step can declare the exact config it needs:

```scala
import etl4s._

case class ApiConfig(url: String, key: String)

val fetchData = Extract("user123")

val processData =
  Transform.requires[ApiConfig, String, String] { cfg => data =>
    s"Processed using ${cfg.key}: $data"
  }

val pipeline = fetchData ~> processData

```

And `provide` your config like so:
```scala
val result = pipeline.provide(ApiConfig("https://api.example.com", "secret-key"))
                     .unsafeRun(())
```

### Environment propagation

**etl4s** automatically resolves the most specific context required when composing multiple steps with different config needs. Just declare capabilities via traits:

```scala
trait HasBase       { def appName: String }
trait HasDateRange  extends HasBase { def startDate: String; def endDate: String }
trait HasFullConfig extends HasDateRange { def dbUrl: String }
```
> ✅ Tip: You can keep config traits minimal and focused (HasLogger, HasDateRange, etc.), then compose them via inheritance. Your pipeline will still work out-of-the-box.

And build a library of reusable steps:
```scala
object ComponentLib {
  val logger = Transform.requires[HasBase, String, String] { cfg =>
                   d => s"[${cfg.appName}] $d" }

  val dater  = Transform.requires[HasDateRange, String, String] { cfg =>
                   d => s"$d (${cfg.startDate} to ${cfg.endDate})" }

  val saver  = Load.requires[HasFulConfig, String, Boolean] { cfg =>
                   d => println(s"Saving to ${cfg.dbUrl}: $d"); true }
}
```
> 🔥 Highlight: etl4s automatically infers the config type required across your whole pipeline.
> You don't need to manually lift or flatMap — just plug things together and .provide what’s needed.


When wired together, etl4s automatically lifts the pipeline to require the combined config:
```scala
import ComponentLib._

case class JobConfig(appName: String,
                     startDate: String,
                     endDate: String,
                     dbUrl: String) extends HasFullConfig

val pipeline = Extract("start") ~> logger ~> dater ~> saver

val myTestConfig = JobConfig("ETL4s", "2023-01-01", "2023-01-31", "jdbc:pg")

pipeline.provide(myTestConfig).unsafeRun(())
```