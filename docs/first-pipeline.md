
Lets build a pipeline with **etl4s** that processes a Spark DataFrame

## Setup
First, import **etl4s** and Spark:
```scala
import etl4s._
import org.apache.spark.sql.{SparkSession, DataFrame}
import org.apache.spark.sql.functions._

/*
 * Init your SparkSession
 */
val spark = SparkSession.builder()
  .appName("Etl4sPipeline")
  .master("local[*]")
  .getOrCreate()

import spark.implicits._
```

Next, create a synthetic user dataset:
```scala
val usersData = Seq(
  (1, "Évariste", "egalois@polytech.fr", 19, "2023-01-15", true),
  (2, "Jean Lannes", "jlannes@example.com", 32, "2023-03-22", true),
  (3, "Clovis", "clovis@gmail.com", 45, "2022-11-08", false),
  (4, "Matthieu", "matthieu@nargothrond.xyz", 28, "2023-06-30", true),
  (5, "Test User", "test@example.com", 37, "2022-09-14", true),
  (6, "Amélie", "apoulain@wanadoo.com", 26, "2023-05-19", false)
)

val usersDF = usersData.toDF("id", "name", "email", "age", "register_date", "active")
```

## Creating etl4s blocks
Next, we create some **etl4s** nodes.

```scala
val getUsers: Extract[Unit, DataFrame] = Extract(_ => usersDF)

val filterUsers = Transform[DataFrame, DataFrame](
  _.filter("register_date >= '2023-01-01' AND active = true")
  )

val saveReport = Load[DataFrame, Unit] { df =>
  println("*** User Report ***")
    df.show()
}
```

## Stitching a pipeline
Stitch our nodes together to make a pipeline:
```scala
val simplePipeline = getUsers ~> filterUsers ~> saveReport
```

## Running your pipeline
```scala
simplePipeline.unsafeRun(())
```

You will see:
```
*** User Report ***
+---+-----------+--------------------+---+-------------+------+
| id|       name|               email|age|register_date|active|
+---+-----------+--------------------+---+-------------+------+
|  1|   Évariste| egalois@polytech.fr| 19|   2023-01-15|  true|
|  2|Jean Lannes| jlannes@example.com| 32|   2023-03-22|  true|
|  4|   Matthieu|matthieu@nargothr...| 28|   2023-06-30|  true|
+---+-----------+--------------------+---+-------------+------+
```

If your pipeline were:
```scala
val pipelineWithInput: Pipeline[DataFrame, Unit] = 
     filterUsers ~> saveReport
```
You would have to provide the type `In` to run it:
```scala
pipelineWithInput.unsafeRun(usersDF)
```


## Making your pipeline config-driven
First, create a config object:

```scala
case class PipelineConfig(
  minAge: Int,
  startDate: String,
  endDate: String,
  outputPath: String
)
```

Then create nodes wrapped in the `Context` they need:
```scala
object DummyPipeline extends Etl4sContext[PipelineConfig] {
  def getFilteredUsers: ExtractWithContext[Unit, DataFrame] = Context { ctx =>
    Extract { (_: Unit) =>
      usersDF
        .filter(col("age") >= ctx.minAge)
        .filter(col("register_date").between(ctx.startDate, ctx.endDate))
    }
  }
  
  def saveResults: LoadWithContext[DataFrame, Unit] = Context { ctx =>
    Load { df =>
      println(s"Would save results to ${ctx.outputPath}")
      df.show()
    }
  }
}
```

Now, we can create a `Pipeline` that depends on configuration:
```scala
import DummyPipeline._

val configPipeline: Context[PipelineConfig, Pipeline[Unit, Unit]] = 
  getFilteredUsers ~> saveResults
```

Build the config and run the pipeline:
```scala
val myConfig = PipelineConfig(
  minAge = 25,
  startDate = "2023-01-01",
  endDate = "2023-06-30",
  outputPath = "data/users_report"
)

/*
 * Provide a `Context`, get back a configured pipeline
 */
val configuredPipeline: Pipeline[Unit, Unit] = 
    configPipeline.provideContext(myConfig)

/*
 * Run the pipeline
 */
configuredPipeline.unsafeRun(())
```
You will see:
```
Would save results to data/users_report
+---+-----------+--------------------+---+-------------+------+
| id|       name|               email|age|register_date|active|
+---+-----------+--------------------+---+-------------+------+
|  2|Jean Lannes| jlannes@example.com| 32|   2023-03-22|  true|
|  4|   Matthieu|matthieu@nargothr...| 28|   2023-06-30|  true|
|  6|     Amélie|apoulain@wanadoo.com| 26|   2023-05-19| false|
+---+-----------+--------------------+---+-------------+------+
```

