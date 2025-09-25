
# Your First Pipeline

Build a complete ETL pipeline in minutes. We'll use Spark, but **etl4s** works with any data processing library.

```bash
scala-cli repl \
  --dep xyz.matthieucourt::etl4s:1.4.1 \
  --dep org.apache.spark:spark-sql_2.13:3.5.0
```

## Basic Pipeline

```scala
import etl4s._
import org.apache.spark.sql.{SparkSession, DataFrame}

val spark = SparkSession.builder().appName("etl4s").master("local[*]").getOrCreate()
import spark.implicits._

// Sample data
val usersDF = Seq(
  (1, "Alice", "alice@example.com", 25, "2023-01-15", true),
  (2, "Bob", "bob@example.com", 32, "2023-03-22", true),
  (3, "Charlie", "charlie@example.com", 19, "2022-11-08", false)
).toDF("id", "name", "email", "age", "register_date", "active")

// Pipeline components
val extract = Extract[Unit, DataFrame](_ => usersDF)
val filter = Transform[DataFrame, DataFrame](_.filter("active = true"))
val report = Load[DataFrame, Unit](_.show())

// Compose and run
val pipeline = extract ~> filter ~> report
pipeline.unsafeRun(())
```

## Config-Driven Pipeline

Make your pipelines configurable and reusable:

```scala
case class Config(minAge: Int, outputPath: String)

val extract = Extract[Unit, DataFrame].requires[Config] { cfg => _ =>
  usersDF.filter(col("age") >= cfg.minAge)
}

val save = Load[DataFrame, Unit].requires[Config] { cfg => df =>
  println(s"Saving to ${cfg.outputPath}")
  df.show()
}

val pipeline = extract ~> save

// Provide config and run
val config = Config(minAge = 25, outputPath = "data/users")
pipeline.provide(config).unsafeRun(())
```

That's it! You've built a complete, configurable ETL pipeline with type safety and clean composition.
