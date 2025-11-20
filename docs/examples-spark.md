# etl4s + Spark

etl4s works alongside Spark. Use it to structure your Spark job logic - extraction, transformations, and loading stay composable and type-safe.

## Basic pattern

```scala
import etl4s._
import org.apache.spark.sql.{SparkSession, DataFrame}

implicit val spark: SparkSession = SparkSession.builder()
  .appName("etl4s-spark")
  .getOrCreate()

// Define your pipeline stages
val extractUsers = Extract[SparkSession, DataFrame] { spark =>
  spark.read.parquet("s3://data/users")
}

val filterActive = Transform[DataFrame, DataFrame] { df =>
  df.filter($"active" === true)
}

val aggregateByRegion = Transform[DataFrame, DataFrame] { df =>
  df.groupBy($"region").count()
}

val writeResults = Load[DataFrame, Unit] { df =>
  df.write.mode("overwrite").parquet("s3://output/results")
}

// Compose the pipeline
val pipeline = 
  extractUsers ~> 
  filterActive ~> 
  aggregateByRegion ~> 
  writeResults

// Run it
pipeline.unsafeRun(spark)
```

## With config injection

```scala
case class SparkConfig(
  inputPath: String,
  outputPath: String,
  partitions: Int
)

val extract = Extract[SparkSession, DataFrame]
  .requires[SparkConfig] { config => spark =>
    spark.read.parquet(config.inputPath)
  }

val transform = Transform[DataFrame, DataFrame]
  .requires[SparkConfig] { config => df =>
    df.repartition(config.partitions)
      .filter($"valid" === true)
  }

val load = Load[DataFrame, Unit]
  .requires[SparkConfig] { config => df =>
    df.write.mode("overwrite").parquet(config.outputPath)
  }

val pipeline = extract ~> transform ~> load

// Provide config and run
val config = SparkConfig(
  inputPath = "s3://data/raw",
  outputPath = "s3://data/processed",
  partitions = 200
)

pipeline.provide(config).unsafeRun(spark)
```

## With telemetry

```scala
val processWithMetrics = Transform[DataFrame, DataFrame] { df =>
  Tel.withSpan("process_users") {
    val count = df.count()
    Tel.addCounter("records_processed", count)
    
    val result = df.filter($"age" > 18)
    val outputCount = result.count()
    Tel.addCounter("records_output", outputCount)
    
    result
  }
}

// Provide your telemetry implementation
implicit val telemetry: Etl4sTelemetry = MySparkMetrics()

pipeline.unsafeRun(spark)
```

## Pattern: Multiple data sources

```scala
val extractUsers = Extract[SparkSession, DataFrame](
  _.read.parquet("s3://data/users")
)

val extractOrders = Extract[SparkSession, DataFrame](
  _.read.parquet("s3://data/orders")
)

val join = Transform[(DataFrame, DataFrame), DataFrame] { case (users, orders) =>
  users.join(orders, users("id") === orders("user_id"))
}

val pipeline = (extractUsers & extractOrders) ~> join ~> writeResults

pipeline.unsafeRun(spark)
```

## Key points

- etl4s doesn't replace Spark - it structures your Spark job logic
- Use `.requires` for configuration (no globals)
- Use `Tel` for business-critical metrics
- Compose Spark operations just like any other ETL stages
- Types ensure DataFrame transformations match up correctly


