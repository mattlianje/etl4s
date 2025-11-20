# etl4s + Flink

etl4s structures your Flink job logic. Define extraction, transformation, and sinks as composable, type-safe stages.

## Basic streaming pattern

```scala
import etl4s._
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment

val env = StreamExecutionEnvironment.getExecutionEnvironment

case class Event(id: String, value: Int, timestamp: Long)

val extractEvents = Extract[StreamExecutionEnvironment, DataStream[Event]] { env =>
  env.addSource(new KafkaSource[Event](...))
}

val filterValid = Transform[DataStream[Event], DataStream[Event]] { stream =>
  stream.filter(_.value > 0)
}

val aggregate = Transform[DataStream[Event], DataStream[(String, Int)]] { stream =>
  stream
    .keyBy(_.id)
    .timeWindow(Time.minutes(5))
    .sum("value")
    .map(e => (e.id, e.value))
}

val sinkResults = Load[DataStream[(String, Int)], Unit] { stream =>
  stream.addSink(new FlinkKafkaProducer(...))
}

val pipeline = 
  extractEvents ~> 
  filterValid ~> 
  aggregate ~> 
  sinkResults

pipeline.unsafeRun(env)
env.execute("etl4s-flink-job")
```

## With config

```scala
case class FlinkConfig(
  kafkaBootstrap: String,
  inputTopic: String,
  outputTopic: String,
  windowMinutes: Int
)

val extract = Extract[StreamExecutionEnvironment, DataStream[Event]]
  .requires[FlinkConfig] { config => env =>
    val props = new Properties()
    props.setProperty("bootstrap.servers", config.kafkaBootstrap)
    
    env.addSource(
      new FlinkKafkaConsumer(config.inputTopic, new EventSchema(), props)
    )
  }

val transform = Transform[DataStream[Event], DataStream[Event]]
  .requires[FlinkConfig] { config => stream =>
    stream
      .keyBy(_.id)
      .timeWindow(Time.minutes(config.windowMinutes))
      .reduce((e1, e2) => e1.copy(value = e1.value + e2.value))
  }

val sink = Load[DataStream[Event], Unit]
  .requires[FlinkConfig] { config => stream =>
    stream.addSink(
      new FlinkKafkaProducer(config.outputTopic, new EventSchema(), ...)
    )
  }

val pipeline = extract ~> transform ~> sink

val config = FlinkConfig(
  kafkaBootstrap = "localhost:9092",
  inputTopic = "events",
  outputTopic = "results",
  windowMinutes = 5
)

pipeline.provide(config).unsafeRun(env)
env.execute()
```

## With telemetry

```scala
val processWithMetrics = Transform[DataStream[Event], DataStream[Event]] { stream =>
  stream.map { event =>
    Tel.addCounter("events_processed", 1)
    if (event.value > 100) {
      Tel.addCounter("high_value_events", 1)
    }
    event
  }
}

implicit val telemetry: Etl4sTelemetry = MyFlinkMetrics()

pipeline.unsafeRun(env)
```

## Pattern: Multiple streams

```scala
val extractUsers = Extract[StreamExecutionEnvironment, DataStream[User]](...)
val extractEvents = Extract[StreamExecutionEnvironment, DataStream[Event]](...)

val join = Transform[(DataStream[User], DataStream[Event]), DataStream[Enriched]] {
  case (users, events) =>
    events
      .connect(users.broadcast())
      .process(new JoinFunction())
}

val pipeline = (extractUsers & extractEvents) ~> join ~> sink

pipeline.unsafeRun(env)
env.execute()
```

## Key points

- etl4s structures Flink job logic, doesn't replace Flink APIs
- Use `.requires` for Kafka configs, window sizes, etc.
- `Tel` metrics can feed into Flink's metrics system
- Compose stream operations as type-safe pipeline stages
- Works with both streaming and batch Flink jobs


