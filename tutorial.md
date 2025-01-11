## Real-world examples using **etl4s**
#### Batch example w/ Spark
Config driven, batch processing pipeline which performs joins and complex aggregations:
```scala
/*
 * Step (1/6) Create some data type to represent the environment + configs
 * our pipeline needs
 */
case class PipelineContext(
 startDate: LocalDate,
 endDate: LocalDate,
 salaryThreshold: Double
)

/*
 * Step (2/6) We create some test data
 */
val employeesDF = Seq(
 (1, "Alice",   1, 100000.0),
 (2, "Bob",     1, 90000.0),
 (3, "Charlie", 2, 120000.0)
).toDF("id", "name", "dept_id", "salary")

val departmentsDF = Seq(
 (1, "Engineering", "NY"),
 (2, "Product",     "SF")
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

/*
 * Step (3/6) Define our building blocks and transformations
 */
val inputDfs = Extract[Unit, Map[String, DataFrame]](_ => Map(
 "employees" -> employeesDF,
 "departments" -> departmentsDF,
 "sales" -> salesDF
))

/*
 * Step (4/6) Handle complex processing + joins
 * just like you are used to in more procedural styles via the `pure` method to
 * introspect on a `Transform`'s input and use intermediate states.
 */
val process: Reader[DataConfig, Transform[Map[String, DataFrame], DataFrame]] = 
 Reader { config =>
   for {
     dfs <- Transform.pure[Map[String, DataFrame]]
     _ = println(s"Processing range: ${config.startDate} to ${config.endDate}")
     
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

/*
 * This loader performs a side effect, and hands the Df off
 * so other pipelines can plug into it and use it for processing
 */
val load = Load[DataFrame, (Unit, DataFrame)](df => {
 df.cache()
 println(s"Writing ${df.count()} records for date range")
 ((), df)
})

/*
 * (Step 5/6) We create a clean pipeline context:
 * This bundles all the dependencies + params your pipeline needs!
 */
val config = PipelineContext(
 startDate       = LocalDate.parse("2023-01-01"),
 endDate         = LocalDate.parse("2023-02-15"),
 salaryThreshold = 100000.0
)

/*
 * (Step 6/6) We stitch our pipeline together and TADA!
 * Now we can run it at the end of the World or plug it into other pipelines
 */
val pipeline =
   inputDfs ~> process.run(config) ~> load

val (_, resultDf) = pipeline.unsafeRun(())
resultDf.show()
```
This outputs:
```
Processing range: 2023-01-01 to 2023-02-15
Writing 3 records for date range
+-------------+-----------+--------+--------+-----------+--------+---------+--------------+
|employee_name| department|location|  salary|total_sales|avg_sale|num_sales|  cost_profile|
+-------------+-----------+--------+--------+-----------+--------+---------+--------------+
|        Alice|Engineering|      NY|100000.0|   350000.0|175000.0|        2|Cost Effective|
|          Bob|Engineering|      NY| 90000.0|   220000.0|110000.0|        2|Cost Effective|
|      Charlie|    Product|      SF|120000.0|   180000.0|180000.0|        1|     High Cost|
+-------------+-----------+--------+--------+-----------+--------+---------+--------------+
```

#### Streaming example w/ Spark
You can just as easily model and process unbounded streams of data with **etl4s**:
```scala
/*
 * Step (1/3) Define a generator or some source
 */
val source = Extract[Unit, DataFrame](_ => 
  spark.readStream
    .format("rate")
    .option("rowsPerSecond", 5)
    .load()
    .select(
      current_timestamp().as("timestamp"),
      expr("value % 3").cast("string").as("user_id"),
      expr("rand() * 100").as("amount")
    )
)

/*
 * Step (2/3) do your windowed aggregations on an unbounded stream
 */
val streamProcessing = Transform[DataFrame, DataFrame](df => 
  df.withWatermark("timestamp", "10 seconds")
    .groupBy(
      window(col("timestamp"), "30 seconds", "10 seconds"),
      col("user_id")
    )
    .agg(
      sum("amount").as("total_amount"),
      count("*").as("num_transactions"),
      avg("amount").as("avg_amount")
    )
)

/*
 * Step (3/3) Perform your streaming writes
 */
val sink = Load[DataFrame, (Unit, DataFrame)](df => {
  val query = df.writeStream
    .format("console")
    .outputMode("update")
    .trigger(Trigger.ProcessingTime("10 seconds"))
    .start()

  println("Query started - will run for demo period...")
  Thread.sleep(60000) /* Just 1 minute for our little demo" */
  query.stop()
  
  ((), df)
})

val pipeline = source ~> streamProcessing ~> sink
val (_, resultDF) = pipeline.unsafeRun(())
```
This will output:
```
Query started - will run for demo period...
-------------------------------------------
Batch: 1
-------------------------------------------
+--------------------+-------+------------------+----------------+------------------+
|              window|user_id|      total_amount|num_transactions|        avg_amount|
+--------------------+-------+------------------+----------------+------------------+
|{2024-12-16 12:58...|      1|1163.0088075440715|              27| 43.07440027941006|
|{2024-12-16 12:58...|      0|1164.7187456389638|              27|43.137731319961624|
|{2024-12-16 12:58...|      0|1164.7187456389638|              27|43.137731319961624|
|{2024-12-16 12:58...|      2|1053.7506535847801|              26| 40.52887129172231|
|{2024-12-16 12:58...|      2|1053.7506535847801|              26| 40.52887129172231|
|{2024-12-16 12:58...|      2|1053.7506535847801|              26| 40.52887129172231|
|{2024-12-16 12:58...|      1|1163.0088075440715|              27| 43.07440027941006|
|{2024-12-16 12:58...|      0|1164.7187456389638|              27|43.137731319961624|
|{2024-12-16 12:58...|      1|1163.0088075440715|              27| 43.07440027941006|
+--------------------+-------+------------------+----------------+------------------+
```

