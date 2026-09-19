# etl4s + Spark

etl4s gives your Spark jobs a spine. Extraction, transformation, and loading
become named, type-safe stages you can compose, test, and rewire - while Spark
still does all the heavy lifting.

```bash
scala-cli repl --dep xyz.matthieucourt::etl4s:1.9.1 --dep org.apache.spark::spark-sql:3.5.0
```

## Basic pattern

The `SparkSession` is a driver singleton, so keep it in scope and let each stage
close over it. Signatures stay about the *data*, and the pipeline just runs.

```scala
import etl4s._
import org.apache.spark.sql.{SparkSession, DataFrame}

implicit val spark: SparkSession = SparkSession.builder()
  .appName("etl4s-spark")
  .getOrCreate()

import spark.implicits._

val extractUsers = Extract {
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

val p = 
     extractUsers ~> filterActive ~> aggregateByRegion ~> writeResults

p.unsafeRun()
```

Because every stage is a plain `DataFrame => DataFrame`, you can unit-test
`filterActive` or `aggregateByRegion` on their own - no `SparkSession` plumbing.

## With config injection

Real jobs vary by environment - paths, partition counts, write modes. Declare
what a stage needs with `.requires`, then `.provide` it once at the edge. The
`SparkConfig` threads through automatically; the session is still captured from
scope.

```scala
case class SparkConfig(
  inputPath: String,
  outputPath: String,
  partitions: Int
)

val extract = Extract[Unit, DataFrame]
  .requires[SparkConfig] { config => _ =>
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

val p = 
     extract ~> transform ~> load

val config = SparkConfig(
  inputPath  = "s3://data/raw",
  outputPath = "s3://data/processed",
  partitions = 200
)

p.provide(config).unsafeRun()
```

## Multiple sources

Combine independent reads with `&`, then join them downstream:

```scala
val extractUsers  = Extract { spark.read.parquet("s3://data/users") }
val extractOrders = Extract { spark.read.parquet("s3://data/orders") }

val join = Transform[(DataFrame, DataFrame), DataFrame] { case (users, orders) =>
  users.join(orders, users("id") === orders("user_id"))
}

val p = 
     (extractUsers & extractOrders) ~> join ~> writeResults

p.unsafeRun()
```

!!! note "Let Spark own the parallelism"
    Use `&`, not `&>` - Spark already parallelizes across the cluster, so there's
    nothing to gain from running the reads on separate threads. For many sources,
    reach for a `Map` instead of a long `&` chain:
    ```scala
    val sources = Map(
      "users"    -> spark.read.parquet("s3://users"),
      "orders"   -> spark.read.parquet("s3://orders"),
      "products" -> spark.read.parquet("s3://products")
    )
    val extract = Extract(sources)
    ```
