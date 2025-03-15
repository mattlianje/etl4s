# Building an Invincible Pipeline with etl4s and the Reader Monad

We'll build a back-office ETL pipeline that uses Reader based DI
to swap out components based on environment needs.


```mermaid
graph TD
    K[Config]
    
    subgraph "ETL Pipeline"
        subgraph "Extract" 
            A[User Source]
            C[Order Source]
            D[Payment Source]
        end
        
        subgraph "Transform"
            B[Combine]
            E[Validate]
            F[Create Report]
        end
        
        subgraph "Load"
            G[DB Sink]
            H[Email Sink]
            I[Log Sink]
        end
        
        A --> B
        C --> B
        D --> E
        B --> F
        E --> F
        F --> G & H & I
    end
    
    K -.->|"DI"| Extract
    K -.->|"DI"| Transform 
    K -.->|"DI"| Load
    
    classDef extract fill:#b5e8ff,stroke:#0077b6
    classDef transform fill:#ffd166,stroke:#e09f3e
    classDef load fill:#a3d9a5,stroke:#2a9134
    classDef config fill:#f4acb7,stroke:#9d4edd
    classDef subgraph fill:none,stroke:#ccc,stroke-dasharray: 5 5
    
    class A,C,D extract
    class B,E,F transform
    class G,H,I load
    class K config
    class Extract,Transform,Load subgraph
```

## Setup
```scala
//> using scala 3.3.1
//> using dep xyz.matthieucourt::etl4s:1.0.0
//> using dep org.apache.spark::spark-sql:3.5.0

import etl4s.*
import org.apache.spark.sql.{SparkSession, DataFrame, functions => F}
import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global

/* Configuration with job dates and processing options */
case class Config(
  environment: String,
  startDate: LocalDate,
  endDate: LocalDate,
  useSparkProcessing: Boolean,
  enableDb: Boolean,
  enableEmail: Boolean,
  dbUrl: String
)

/* Create configurations for different environments */
val dev = Config(
  "dev", 
  LocalDate.now().minusDays(7), 
  LocalDate.now(),
  false, 
  false, 
  false, 
  "jdbc:h2:mem:test"
)

val prod = Config(
  "prod", 
  LocalDate.now().minusDays(30), 
  LocalDate.now(),
  true, 
  true, 
  true, 
  "jdbc:mysql://prod-db/app"
)

/* Spark session for DataFrame operations */
lazy val spark = SparkSession.builder()
  .appName("ETL4s Pipeline")
  .master("local[*]")
  .getOrCreate()
```

## Config aware sources
```scala
val userSource = Reader[Config, Extract[Unit, DataFrame]] { config =>
  Extract { _ => 
    println(s"[${config.environment}] Getting users from ${config.startDate} to ${config.endDate}")
    
    // Create sample user data
    import spark.implicits._
    Seq(
      (1, "john_doe", "premium", "2023-01-15"),
      (2, "jane_smith", "basic", "2022-11-20"),
      (3, "bob_jones", "premium", "2024-02-01")
    ).toDF("user_id", "username", "account_type", "signup_date")
  }
}

val orderSource = Reader[Config, Extract[Unit, DataFrame]] { config =>
  Extract { _ =>
    println(s"[${config.environment}] Loading orders from ${config.startDate} to ${config.endDate}")
    
    // Create sample order data
    import spark.implicits._
    Seq(
      (101, 1, "2024-03-01", 99.99, "completed"),
      (102, 1, "2024-03-05", 49.95, "processing"),
      (103, 2, "2024-03-02", 149.99, "completed"),
      (104, 3, "2024-03-07", 29.99, "shipped")
    ).toDF("order_id", "user_id", "order_date", "amount", "status")
  }
}

val paymentSource = Reader[Config, Extract[Unit, DataFrame]] { config =>
  Extract { _ =>
    println(s"[${config.environment}] Getting payments from ${config.startDate} to ${config.endDate}")
    
    // Create sample payment data
    import spark.implicits._
    Seq(
      (501, 101, "2024-03-01", 99.99, "credit_card"),
      (502, 103, "2024-03-02", 149.99, "paypal"),
      (503, 102, "2024-03-05", 49.95, "bank_transfer")
    ).toDF("payment_id", "order_id", "payment_date", "amount", "payment_method")
  }
}
```

## Transformations
```scala
// Process user and order data together
val processCustomerOrders = Reader[Config, Transform[(DataFrame, DataFrame), DataFrame]] { config =>
  Transform { case (userDf, orderDf) => 
    println("[SPARK] Processing with DataFrame joins and aggregations")
    
    // Join users and orders, then aggregate by user
    userDf
      .join(orderDf, "user_id")
      .groupBy("user_id", "username", "account_type")
      .agg(
        F.count("order_id").as("order_count"),
        F.sum("amount").as("total_spent"),
        F.max("order_date").as("last_order_date")
      )
      .where(F.col("account_type") === "premium")
      .orderBy(F.col("total_spent").desc)
  }
}

// Process payment data
val processPayments = Reader[Config, Transform[DataFrame, DataFrame]] { config =>
  Transform { paymentDf => 
    val dateCondition = F.col("payment_date").between(
      config.startDate.toString, 
      config.endDate.toString
    )
    
    // Filter by date range and add success indicator
    paymentDf
      .where(dateCondition)
      .withColumn("days_to_process", 
        F.datediff(F.current_date(), F.to_date(F.col("payment_date"))))
      .withColumn("payment_status", 
        F.when(F.col("payment_method") === "credit_card", "fast")
         .when(F.col("payment_method") === "paypal", "medium")
         .otherwise("slow"))
  }
}

// Create final report combining customer orders and payments
val createReport = Reader[Config, Transform[(DataFrame, DataFrame), String]] { config =>
  Transform { case (customerOrdersDf, paymentsDf) =>
    // Join customer orders with payments
    val reportDf = customerOrdersDf
      .join(
        paymentsDf,
        customerOrdersDf("order_id") === paymentsDf("order_id"),
        "left_outer"
      )
      .select(
        "username", 
        "account_type", 
        "order_count", 
        "total_spent", 
        "payment_method",
        "payment_status"
      )
    
    // For simplicity, collect just top results to a string
    val topCustomers = reportDf
      .orderBy(F.col("total_spent").desc)
      .limit(5)
      .collect()
      .map(row => 
        s"${row.getAs[String]("username")}: $${row.getAs[Double]("total_spent")} (${row.getAs[String]("account_type")})"
      )
      .mkString("\n")
    
    s"REPORT [${config.environment}] for ${config.startDate} to ${config.endDate}:\n$topCustomers"
  }
}
```

## Configurable sinks
```scala
// Sinks that enable/disable based on configuration
val dbSink = Reader[Config, Load[String, String]] { config =>
  if (config.enableDb) {
    Load { report =>
      println(s"[DB] Saving to ${config.dbUrl}: $report")
      report
    }
  } else {
    Load { report => report }
  }
}

val emailSink = Reader[Config, Load[String, Unit]] { config =>
  if (config.enableEmail) {
    Load { report => 
      println(s"[EMAIL] Sending report for period ${config.startDate} to ${config.endDate}")
    }
  } else {
    Load { _ => () }
  }
}

val logSink = Reader[Config, Load[String, Unit]] { _ =>
  Load { report => println(s"[LOG] Writing: $report") }
}

val resultCollector = Load[(String, Unit, Unit), String] { case (dbResult, _, _) => dbResult }
```

## Building the Pipeline
```scala
// Assemble the complete pipeline using dependency injection
val buildPipeline = for {
  // Get configured components
  users <- userSource
  orders <- orderSource
  payments <- paymentSource
  processOrders <- processCustomerOrders
  processPayment <- processPayments
  report <- createReport
  db <- dbSink
  email <- emailSink
  log <- logSink
  
  // Build the pipeline structure
  pipeline = (
    (users & orders) ~> processOrders &
    (payments ~> processPayment)
  ) ~> report ~>
  (db & email & log).zip ~> resultCollector
  
} yield pipeline
```

## Running the Pipeline
```scala
@main def run(): Unit = {
  try {
    println("=== DEVELOPMENT MODE ===")
    println(s"Date range: ${dev.startDate} to ${dev.endDate}")
    val devResult = buildPipeline.run(dev).unsafeRun(())
    
    println("\n=== PRODUCTION MODE ===")
    println(s"Date range: ${prod.startDate} to ${prod.endDate}")
    val prodResult = buildPipeline.run(prod).unsafeRun(())
    
    println(s"\nFinal result: $prodResult")
  } finally {
    spark.stop()
  }
}
```
