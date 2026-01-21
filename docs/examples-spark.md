# etl4s + Spark

etl4s works alongside Spark. Use it to structure your Spark job logic - extraction, transformations, and loading stay composable and type-safe.

```bash
scala-cli repl --dep io.github.mattlianje::etl4s:1.7.1 --dep org.apache.spark::spark-sql:3.5.0
```

## Basic pattern

```scala
import etl4s._
import org.apache.spark.sql.{SparkSession, DataFrame}

implicit val spark: SparkSession = SparkSession.builder()
  .appName("etl4s-spark")
  .getOrCreate()

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

val pipeline =
  extractUsers ~>
  filterActive ~>
  aggregateByRegion ~>
  writeResults

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

val config = SparkConfig(
  inputPath = "s3://data/raw",
  outputPath = "s3://data/processed",
  partitions = 200
)

pipeline.provide(config).unsafeRun(spark)
```

## Multiple data sources

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

!!! note
    Use `&` not `&>` with Spark - Spark handles parallelism internally. For many sources, use a Map instead of chaining `&`:
    ```scala
    val sources = Map(
      "users" -> spark.read.parquet("s3://users"),
      "orders" -> spark.read.parquet("s3://orders"),
      "products" -> spark.read.parquet("s3://products")
    )
    val extract = Extract(sources)
    ```
