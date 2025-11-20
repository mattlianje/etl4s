# Performance Tips

How to get the most out of etl4s.

## etl4s overhead is minimal

Nodes wrap functions. The `~>` operator does type checking at compile time, not runtime. The wrapper overhead is negligible compared to actual data processing.

That said, here's how to optimize:

## Avoid excessive node wrapping

**Don't do this:**

```scala
val step1 = Transform[Int, Int](_ + 1)
val step2 = Transform[Int, Int](_ + 1)
val step3 = Transform[Int, Int](_ + 1)
// ... 100 tiny nodes

val pipeline = step1 ~> step2 ~> step3 // ... ~> step100
```

**Do this:**

```scala
val combinedTransform = Transform[Int, Int] { x =>
  // do all the work in one node
  (1 to 100).foldLeft(x)((acc, _) => acc + 1)
}
```

**Why:** Each `~>` is a function call. For heavy computation, that's nothing. For trivial operations, it's overhead.

## Use `&` strategically

Parallelism has overhead. Only use `&` when stages are independently expensive.

**Good use of `&`:**

```scala
// Each stage does heavy I/O or computation
val pipeline = extract ~> (callApiA & callApiB & callApiC) ~> combine
```

**Bad use of `&`:**

```scala
// Parallelizing cheap operations
val double = Transform[Int, Int](_ * 2)
val triple = Transform[Int, Int](_ * 3)

val pipeline = Extract(5) ~> (double & triple) ~> combine
```

**Why:** Thread coordination costs more than just doing `double` then `triple` sequentially.

## Batch operations in transforms

**Don't do this:**

```scala
val processRecords = Transform[List[Record], List[Result]] { records =>
  records.map { record =>
    // Make individual API call per record
    callApi(record)
  }
}
```

**Do this:**

```scala
val processRecords = Transform[List[Record], List[Result]] { records =>
  // Batch API call
  callApiBatch(records)
}
```

**Why:** Network calls, database queries, file I/O - batch them whenever possible.

## Tracing and telemetry are opt-in

**Zero cost by default:**

```scala
// No tracing overhead
pipeline.unsafeRun(input)

// Tracing enabled only when you use it
pipeline.unsafeRunTrace(input)
```

**Tel is zero-cost until you provide implementation:**

```scala
// No overhead - Tel calls are no-ops
Tel.addCounter("requests", 1)

// Only adds overhead when you provide implementation
implicit val telemetry: Etl4sTelemetry = MyPrometheus()
Tel.addCounter("requests", 1)  // now it does something
```

## Avoid unnecessary config indirection

**Don't do this if config is static:**

```scala
val transform = Transform[Data, Data]
  .requires[Config] { config => data =>
    data.copy(value = data.value * config.multiplier)
  }

// Config never changes
val config = Config(multiplier = 2)
pipeline.provide(config).unsafeRun(input)
```

**Do this:**

```scala
// Hardcode if it never changes
val transform = Transform[Data, Data] { data =>
  data.copy(value = data.value * 2)
}

pipeline.unsafeRun(input)
```

**Why:** `.requires` adds a layer of indirection. Only use it when you actually need runtime configuration.

## Reuse pipeline instances

**Don't do this:**

```scala
// Creating pipeline in hot loop
for (i <- 1 to 1000) {
  val pipeline = extract ~> transform ~> load
  pipeline.unsafeRun(data(i))
}
```

**Do this:**

```scala
// Create once, reuse
val pipeline = extract ~> transform ~> load

for (i <- 1 to 1000) {
  pipeline.unsafeRun(data(i))
}
```

**Why:** Pipeline composition has a cost. Do it once, not repeatedly.

## Use appropriate data structures

etl4s doesn't impose data structures. Use what's efficient for your workload:

```scala
// Processing millions of records?
// Use Vector, not List
val transform = Transform[Vector[Record], Vector[Result]] { records =>
  records.map(process)
}

// Need constant-time lookup?
// Use Map
val transform = Transform[Map[String, Data], Map[String, Result]] { data =>
  data.map { case (k, v) => k -> process(v) }
}
```

## Profile before optimizing

**Use tracing to find bottlenecks:**

```scala
val trace = pipeline.unsafeRunTrace(input)

println(s"Total time: ${trace.timeElapsedMillis}ms")
trace.logs.foreach(println)
```

**Add timing to specific stages:**

```scala
val timedTransform = transform.tap { _ =>
  Trace.log(s"Transform at ${System.currentTimeMillis()}")
}

val pipeline = extract ~> 
  timedTransform ~> 
  load
```

## When to use Cats Effect or ZIO instead

etl4s is lightweight. If you need:

- Async/reactive streams
- Sophisticated resource management
- Complex concurrency patterns
- Integration with Cats/ZIO ecosystem

Use Cats Effect or ZIO directly. They're more powerful but have steeper learning curves.

etl4s is for teams that want structure without committing to a full effect system.

## Key takeaways

- etl4s overhead is minimal - focus on your actual data processing
- Use `&` for expensive operations, not cheap ones
- Batch I/O operations
- Tracing and telemetry are opt-in
- Create pipelines once, reuse them
- Profile first, optimize second


