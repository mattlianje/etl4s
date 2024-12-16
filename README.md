<p align="center">
  <img src="pix/etl4s.png" width="700">
</p>

# etl4s _(pre-𝛼)_
**Powerful, whiteboard-style ETL**

A lightweight, zero-dependency, library for writing type-safe, beautiful ✨🍰  data flows in functional Scala. 

## Features
- White-board style ETL
- Monadic composition for sequencing pipelines
- Drop **Etl4s.scala** into any Scala project like a header file
- Type-safe, compile-time checked pipelines
- Effortless concurrent execution of parallelizable tasks
- Built in retry/on-failure mechanism for nodes + pipelines

## Get started
> [!WARNING]  
> Releases sub `1.0.0` are experimental - breaking API changes might happen

**etl4s** is on the MavenCentral repo:
```scala
"io.github.mattlianje" % "etl4s_2.13" % "0.0.1"
```

Or try the latest `master` in your scala repl:
```bash
curl -Ls raw.githubusercontent.com/mattlianje/etl4s/master/Etl4s.scala > .Etl4s.swp.scala && scala-cli repl .Etl4s.swp.scala
```

## Core Concepts
**etl4s** has 4 building blocks

#### `Pipeline[-In, +Out]`
A fully created pipeline composed of nodes chained with `~>`. It takes a type `In` and gives a `Out` when run.
Call `unsafeRun()` to "run-or-throw" - `safeRun()` will yield a `Try[Out]` Monad.

#### `Node[-In, +Out]`
`Node` Is the base abstraction of **etl4s**. A pipeline is stitched out of nodes. Nodes are just abstractions which defer the application of some run function: `In => Out`.
Nodes are monadic having unit operation the `pure` method. The node types are:

- ##### `Extract[-In, +Out]`
The start of your pipeline. An extract can either be plugged into another function or pipeline or produce an element "purely" with `Extract(2)`. This is shorthand for `val e: Extract[Unit, Int] = Extract(_ => 2)`

- ##### `Transform[-In, +Out]`
A `Node` that represent a transformation. It can be composed with other nodes via `andThen`

- ##### `Load[-In, +Out]` 
A `Node` used to represent the end of a pipeline.

## Of note...
- Ultimately - these nodes and pipelines are just reifications of functions and values (with a few niceties like built in retries and Future based parallelism).
- Chaotic, framework/infra-coupled ETL codebases that grow without an imposed discipline drive dev-teams and data-orgs to their knees.
- **etl4s** is a little DSL to enforce discipline, type-safety and re-use of pure functions - 
and see [functional ETL](https://maximebeauchemin.medium.com/functional-data-engineering-a-modern-paradigm-for-batch-data-processing-2327ec32c42a) for what it is... and could be.


## Examples
**etl4s** won't let you chain together "blocks" that don't fit together:
```scala
 val fiveExtract: Extract[Unit, Int]        = Extract(5)
 val exclaim:     Transform[String, String] = Transform(_ + "!")

 fiveExtract ~> exclaim
```
The above will not compile with:
```shell
-- [E007] Type Mismatch Error: -------------------------------------------------
4 | fiveExtract ~> exclaim
  |                ^^^^^^^
  |                Found:    (exclaim : Transform[String, String])
  |                Required: Node[Int, Any]
```

##### Chain pipelines together:
Connect the output of two pipelines to a third:
```scala
val fetchUser:      Transform[String, String]= Transform(id => s"Fetching user $id")
val loadUser:       Load[String, String]     = Load(msg => s"User loaded: $msg")

val fetchOrder:     Transform[Int, String]   = Transform(id => s"Fetching order $id")
val loadOrder:      Load[String, String]     = Load(msg => s"Order loaded: $msg")

val userPipeline:   Pipeline[Unit, String]   = Extract("user123") ~> fetchUser ~> loadUser
val ordersPipeline: Pipeline[Unit, String]   = Extract(42) ~> fetchOrder ~> loadOrder

val combinedPipeline: Pipeline[Unit, String] = (for {
  userData  <- userPipeline
  orderData <- ordersPipeline
} yield {
  Extract(s"$userData | $orderData") ~> Transform { _.toUpperCase } ~> Load { x => s"Final result: $x"}
}).flatten

combinedPipeline.unsafeRun(())

// "Final result: USER LOADED: FETCHING USER USER123 | ORDER LOADED: FETCHING ORDER 42"
```

##### Config-driven pipelines
Use the built in `Reader` monad:
```scala
case class ApiConfig(url: String, key: String)
val config = ApiConfig("https://api.com", "secret")

val fetchUser = Reader[ApiConfig, Transform[String, String]] { config =>
  Transform(id => s"Fetching user $id from ${config.url}")
}

val loadUser = Reader[ApiConfig, Load[String, String]] { config =>
  Load(msg => s"User loaded with key ${config.key}: $msg")
}

val configuredPipeline = for {
  userTransform <- fetchUser
  userLoader    <- loadUser
} yield Extract("user123") ~> userTransform ~> userLoader

/* Run with config */
val result = configuredPipeline.run(config).unsafeRun(())

// "User loaded with key secret: Fetching user user123 from https://api.com"
```

##### Parallelize tasks
Parallelize tasks with task groups using `&>` or sequence them with `&`:
```scala
val slowLoad = Load[String, Int] { s => Thread.sleep(100); s.length }

/* Using &> for parallelized tasks */
time("Using &> operator") {
  val pipeline = Extract("hello") ~> (slowLoad &> slowLoad &> slowLoad)
  pipeline.unsafeRun(())
}

/* Using & for sequenced tasks */
time("Using & operator") {
    val pipeline = Extract("hello") ~> (slowLoad & slowLoad & slowLoad)
    pipeline.unsafeRun(())
}

/*
 *   Using &> operator took 100ms
 *   Using & operator took  305ms
 */
```

##### Retry and onFailure
Give individual `Nodes` or whole `Pipelines` retry capability using `.withRetry(<YOUR_CONF>: RetryConfig)` 
and the batteries included `RetryConfig` which does exponential backoff:
```scala
case class RetryConfig( /* default config */
  maxAttempts: Int = 3,
  initialDelay: Duration = 100.millis,
  backoffFactor: Double = 2.0
)

val transform = Transform[Int, String] { n => 
  if (math.random() < 0.5) throw new Exception("Random failure")
  s"Processed $n"
}

val pipeline: Pipeline[Unit, String] = Extract(42) ~> transform.withRetry(RetryConfig())
val result:   Try[String]            = pipeline.safeRun(())
```

##### Real-world example with Spark
```scala
 /* Create some pipeline conf */
 case class PipelineContext(
   startDate: LocalDate,
   endDate: LocalDate,
   salaryThreshold: Double
 )

 /* Create our data to process */
 val employeesDF = Seq(
   (1, "Alice", 1, 100000.0),
   (2, "Bob", 1, 90000.0),
   (3, "Charlie", 2, 120000.0)
 ).toDF("id", "name", "dept_id", "salary")

 val departmentsDF = Seq(
   (1, "Engineering", "NY"),
   (2, "Product", "SF")
 ).toDF("id", "name", "location")

 val salesDF = Seq(
       (1, LocalDate.parse("2023-01-15"), 150000.0),
       (1, LocalDate.parse("2023-02-01"), 200000.0),
       (1, LocalDate.parse("2023-03-15"), 150000.0),
       (2, LocalDate.parse("2023-01-20"), 100000.0),
       (2, LocalDate.parse("2023-02-15"), 120000.0),
       (3, LocalDate.parse("2023-01-10"), 180000.0),
       (3, LocalDate.parse("2023-02-20"), 270000.0)
 ).toDF("emp_id", "date", "amount")

 /* Define our building blocks and transformations */
 val inputDfs = Extract[Unit, Map[String, DataFrame]](_ => Map(
   "employees" -> employeesDF,
   "departments" -> departmentsDF,
   "sales" -> salesDF
 ))

 val process: Reader[DataConfig, Transform[Map[String, DataFrame], DataFrame]] = 
   Reader { config =>
     for {
       /*
        * Tranforms can introspect on their input use some intermediate states
        * just like you are used to in more procedural styles via pure
        * + monadic comprehensions
        */
       dfs <- Transform.pure[Map[String, DataFrame]]
       
       salesInRange = dfs("sales")
         .where(col("date").between(config.startDate, config.endDate))
         .groupBy("emp_id")
         .agg(
           sum("amount").as("total_sales"),
           avg("amount").as("avg_sale"),
           count("*").as("num_sales")
         )
       
     result <- Transform[Map[String, DataFrame], DataFrame](_ => {
       val employees = dfs("employees")
       val departments = dfs("departments")
       
       employees
         .join(departments, employees.col("dept_id") === departments.col("id"))
         .join(salesInRange, employees.col("id") === salesInRange.col("emp_id"))
         .select(
           employees.col("name").as("employee_name"),
           departments.col("name").as("department"),
           col("location"),
           col("salary"),
           col("total_sales"),
           col("avg_sale"),
           col("num_sales"),
           when(col("salary") > config.salaryThreshold, "High Cost")
             .otherwise("Cost Effective").as("cost_profile")
         )
     })
     } yield result
   }

 /* This loader performs a side effect, and hands the Df off
  * so other pipelines can plug into it and use it for processing
  */
 val load = Load[DataFrame, (Unit, DataFrame)](df => {
   df.cache()
   println(s"Writing ${df.count()} records for date range")
   ((), df)
 })

 /* We create a clean pipeline context:
  * This bundles all the dependencies + params your pipeline needs!
  *
 val config = PipelineContext(
   startDate       = LocalDate.parse("2023-01-01"),
   endDate         = LocalDate.parse("2023-02-15"),
   salaryThreshold = 100000.0
 )
 
 val pipeline =
     inputDfs ~> process.run(config) ~> load

 val (_, resultDf) = pipeline.unsafeRun(())
 resultDf.show()
```
This outputs:
```
Writing 3 records for date range
+-------------+-----------+--------+--------+-----------+--------+---------+--------------+
|employee_name| department|location|  salary|total_sales|avg_sale|num_sales|  cost_profile|
+-------------+-----------+--------+--------+-----------+--------+---------+--------------+
|        Alice|Engineering|      NY|100000.0|   350000.0|175000.0|        2|Cost Effective|
|          Bob|Engineering|      NY| 90000.0|   220000.0|110000.0|        2|Cost Effective|
|      Charlie|    Product|      SF|120000.0|   180000.0|180000.0|        1|     High Cost|
+-------------+-----------+--------+--------+-----------+--------+---------+--------------+
```


## Inspiration
- Debasish Ghosh's [Functional and Reactive Domain Modeling](https://www.manning.com/books/functional-and-reactive-domain-modeling)
- [Akka Streams DSL](https://doc.akka.io/libraries/akka-core/current/stream/stream-graphs.html#constructing-graphs)
- Various Rich Hickey talks


