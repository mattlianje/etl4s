# etl4s + Spark

A Spark job tends to grow into one long method - reads, filters, joins and writes
all tangled together, where you can't test a single step in isolation.

etl4s gives it a spine: extraction, transformation and loading become named,
type-safe stages you wire with `~>`. Spark still does all the heavy lifting -
etl4s just gives the job a shape you can read, test, and reuse.

```bash
scala-cli repl --dep xyz.matthieucourt::etl4s:latest.release  \
               --dep org.apache.spark::spark-sql:3.5.0
```

## Structure a Spark job

The `SparkSession` is a driver singleton, so keep it in scope and let each stage
close over it. Signatures stay about the *data*, and the job reads top to bottom.

```scala
import etl4s._
import org.apache.spark.sql.{SparkSession, DataFrame}

implicit val spark: SparkSession = SparkSession.builder()
  .appName("etl4s-spark")
  .getOrCreate()

import spark.implicits._

val extractUsers  = Node { spark.read.parquet("s3://data/users") }
val filterActive  = Node[DataFrame, DataFrame](_.filter($"active" === true))
val aggByRegion   = Node[DataFrame, DataFrame](_.groupBy($"region").count())
val writeResults  = Node[DataFrame, Unit](_.write.mode("overwrite").parquet("s3://..."))

val job = 
     extractUsers ~> filterActive ~> aggregateByRegion ~> writeResults

job.unsafeRun()
```

## Test a stage without a cluster

Each stage is just a `DataFrame => DataFrame` value, so you can run one on a tiny
in-memory DataFrame - no job to launch, no session to mock:

```scala
val sample = Seq(
  ("alice", true),
  ("bob",   false)
).toDF("name", "active")

val active = filterActive.unsafeRun(sample)

active.count() // 1
```

## Inject config per environment

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

val job = 
     extract ~> transform ~> load

val config = SparkConfig(
  inputPath  = "s3://data/raw",
  outputPath = "s3://data/processed",
  partitions = 200
)

job.provide(config).unsafeRun()
```

## Combine multiple sources

Fan out independent reads with `&`, then join them downstream:

```scala
val extractUsers  = Extract { spark.read.parquet("s3://data/users") }
val extractOrders = Extract { spark.read.parquet("s3://data/orders") }

val join = Transform[(DataFrame, DataFrame), DataFrame] { case (users, orders) =>
  users.join(orders, users("id") === orders("user_id"))
}

val job = 
     (extractUsers & extractOrders) ~> join ~> writeResults

job.unsafeRun()
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
