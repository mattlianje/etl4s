
This example shows how to use **etl4s** to build a real ETL pipeline with Apache Spark and minimal setup. You'll learn to:

- Define reusable pipeline nodes
- Compose and run them
- Inject configuration cleanly

### Setup in REPL
Run the following in your terminal to start a REPL with all dependencies:
```bash
scala-cli repl \
  --scala 2.13 \
  --dep xyz.matthieucourt::etl4s:1.4.1 \
  --dep org.apache.spark:spark-sql_2.13:3.5.0
```

### (1/2) Dataset + Basic Pipeline
```scala
import etl4s._
import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._

val spark = SparkSession.builder()
  .appName("etl4sDemo")
  .master("local[*]")
  .getOrCreate()

import spark.implicits._

val usersDF = Seq(
  (1, "Évariste", "egalois@polytech.fr", 19, "2023-01-15", true),
  (2, "Jean Lannes", "jlannes@example.com", 32, "2023-03-22", true),
  (3, "Clovis", "clovis@gmail.com", 45, "2022-11-08", false),
  (4, "Matthieu", "matthieu@nargothrond.xyz", 28, "2023-06-30", true),
  (5, "Test User", "test@example.com", 37, "2022-09-14", true),
  (6, "Amélie", "apoulain@wanadoo.com", 26, "2023-05-19", false)
).toDF("id", "name", "email", "age", "register_date", "active")

val extract = Extract[Unit, DataFrame](_ => usersDF)

val filter = Transform[DataFrame, DataFrame](
  _.filter("active = true AND register_date >= '2023-01-01'")
)

val report = Load[DataFrame, Unit] { df =>
  println("*** User Report ***")
  df.show()
}

val pipeline = extract ~> filter ~> report

pipeline.unsafeRun(())
```

You will see:
```
+---+-----------+--------------------+---+-------------+------+
| id|       name|               email|age|register_date|active|
+---+-----------+--------------------+---+-------------+------+
|  1|   Évariste| egalois@polytech.fr| 19|   2023-01-15|  true|
|  2|Jean Lannes| jlannes@example.com| 32|   2023-03-22|  true|
|  4|   Matthieu|matthieu@nargothr...| 28|   2023-06-30|  true|
+---+-----------+--------------------+---+-------------+------+
```

### (2/2) Config-driven pipeline
```scala
case class ReportConfig(
  minAge: Int,
  startDate: String,
  endDate: String,
  outputPath: String
)

val extract = Node.requires[ReportConfig, Unit, DataFrame] { cfg => _ =>
  usersDF
    .filter(col("age") >= cfg.minAge)
    .filter(col("register_date").between(cfg.startDate, cfg.endDate))
}

val save = Node.requires[ReportConfig, DataFrame, Unit] { cfg => df =>
  println(s"Saving results to ${cfg.outputPath}")
  df.show()
}

val reportPipeline = extract ~> save

val config = ReportConfig(
  minAge = 25,
  startDate = "2023-01-01",
  endDate = "2023-06-30",
  outputPath = "data/users_report"
)

val finalPipeline = reportPipeline.provide(config)
finalPipeline.unsafeRun(())
```
Output:
```
Saving results to data/users_report
+---+-----------+--------------------+---+-------------+------+
| id|       name|               email|age|register_date|active|
+---+-----------+--------------------+---+-------------+------+
|  2|Jean Lannes| jlannes@example.com| 32|   2023-03-22|  true|
|  4|   Matthieu|matthieu@nargothr...| 28|   2023-06-30|  true|
|  6|     Amélie|apoulain@wanadoo.com| 26|   2023-05-19| false|
+---+-----------+--------------------+---+-------------+------+
```
