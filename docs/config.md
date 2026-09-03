---
api:
  - sig: ".requires[T]"
  - sig: ".provide(env)"
  - sig: ".provideContext(env)"
  - sig: "Reader[T, Node]"
  - sig: "Context[T]"
---

# Configuration

When writing pipelines, you often need to:

- Pass database URLs, API keys, thresholds to various stages
- Avoid threading config through every function signature
- Keep stages testable by swapping config at the edge

`.requires` declares what a node needs. `.provide` supplies it once at the top. Config flows through automatically - it's just a Reader monad (`Config => Node[In, Out]`) with some syntax.

```scala
import etl4s._

case class Cfg(key: String)

val readData   = Extract("data")
val tagWithKey = Transform[String, String].requires[Cfg] { cfg => data =>
  s"${cfg.key}: $data"
}

val pipeline = readData ~> tagWithKey

pipeline.provide(Cfg("secret")).unsafeRun(())
```
You will get:
```
"secret: data"
```

## Config Propagation

Build modular configs with traits. etl4s infers what your pipeline needs:

```scala
trait HasDb { def dbUrl: String }
trait HasAuth { def apiKey: String }

val save = Load[String, Unit].requires[HasDb] { cfg => data =>
  println(s"Saving to ${cfg.dbUrl}: $data")
}

val fetch = Extract[Unit, String].requires[HasAuth] { cfg => _ =>
  s"Fetched with ${cfg.apiKey}"
}

val toUpper = Transform[String, String](_.toUpperCase)

case class AppConfig(dbUrl: String, apiKey: String) extends HasDb with HasAuth

val pipeline = fetch ~> toUpper ~> save

pipeline.provide(AppConfig("jdbc:pg", "secret-key")).unsafeRun(())
```

`.requires[T]` turns a node into a `Reader[T, Node]`. The composition operators (`~>`, `&`,
`&>`, `>>`) work directly on these config-aware nodes, and three simple rules govern how
requirements flow.

**1. Plain nodes connect straight to config-aware ones.** A plain `Node` requires nothing,
so mixing it in adds no requirement. The pipeline still asks only for what the Reader nodes
need:

```scala
val fetch   = Extract[Unit, String].requires[HasAuth] { c => _ => s"got ${c.apiKey}" }
val toUpper = Transform[String, String](_.toUpperCase) // plain node

val p = fetch ~> toUpper                 // still Reader[HasAuth, Node[Unit, String]]
p.provide(AppConfig("jdbc:pg", "key")).unsafeRun(())
```

<div class="diagram">
<svg xmlns="http://www.w3.org/2000/svg" width="520" height="150" viewBox="0 0 520 150" font-family="'Helvetica Neue', Helvetica, Arial, sans-serif">
<rect x="16" y="34" width="120" height="82" rx="12" fill="#009688" fill-opacity="0.09" stroke="#009688" stroke-width="1.5"/>
<text x="28" y="52" font-size="12" fill="#009688">HasAuth</text>
<rect x="40" y="62" width="72" height="34" rx="7" fill="none" stroke="currentColor" stroke-width="1.3"/>
<text x="76" y="83" text-anchor="middle" font-size="13" fill="currentColor">fetch</text>
<text x="152" y="85" text-anchor="middle" font-size="15" fill="currentColor" opacity="0.45">~&gt;</text>
<g opacity="0.6"><rect x="172" y="62" width="92" height="34" rx="7" fill="none" stroke="currentColor" stroke-width="1.3"/><text x="218" y="83" text-anchor="middle" font-size="13" fill="currentColor">toUpper</text></g>
<text x="284" y="85" text-anchor="middle" font-size="16" fill="currentColor" opacity="0.45">=</text>
<rect x="302" y="34" width="204" height="82" rx="12" fill="#009688" fill-opacity="0.09" stroke="#009688" stroke-width="1.5"/>
<text x="314" y="52" font-size="12" fill="#009688">HasAuth</text>
<rect x="320" y="62" width="168" height="34" rx="7" fill="none" stroke="currentColor" stroke-width="1.3"/>
<text x="404" y="83" text-anchor="middle" font-size="13" fill="currentColor">fetch ~&gt; toUpper</text>
</svg>
</div>

**2. Unrelated configs merge to an intersection.** When two nodes require different configs,
etl4s combines them to `T1 & T2`, here `HasAuth & HasDb`. You then `.provide` a single value
that lives in the overlap (the `AppConfig` above, which extends both).

<div class="diagram">
<svg xmlns="http://www.w3.org/2000/svg" width="340" height="190" viewBox="0 0 340 190" font-family="'Helvetica Neue', Helvetica, Arial, sans-serif">
<circle cx="130" cy="98" r="74" fill="#009688" fill-opacity="0.10" stroke="#009688" stroke-width="1.5"/>
<circle cx="210" cy="98" r="74" fill="#7c4dff" fill-opacity="0.10" stroke="#7c4dff" stroke-width="1.5"/>
<text x="96" y="102" text-anchor="middle" font-size="13" fill="#009688">HasAuth</text>
<text x="246" y="102" text-anchor="middle" font-size="13" fill="#7c4dff">HasDb</text>
</svg>
</div>

**3. A subtype absorbs its supertype.** If one node needs `HasDb` and another needs a subtype
`AppConfig <: HasDb`, the merged requirement is just `AppConfig`. The more specific type (the
smaller set) wins, so there's nothing extra to provide.

<div class="diagram">
<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200" viewBox="0 0 320 200" font-family="'Helvetica Neue', Helvetica, Arial, sans-serif">
<circle cx="160" cy="102" r="82" fill="#7c4dff" fill-opacity="0.08" stroke="#7c4dff" stroke-width="1.5"/>
<circle cx="160" cy="118" r="46" fill="#009688" fill-opacity="0.14" stroke="#009688" stroke-width="1.5"/>
<text x="160" y="46" text-anchor="middle" font-size="13" fill="#7c4dff">HasDb</text>
<text x="160" y="122" text-anchor="middle" font-size="13" fill="#009688">AppConfig</text>
</svg>
</div>

`.provide` also has an alias, `.provideContext`, which does the same thing:

```scala
pipeline.provideContext(AppConfig("jdbc:pg", "secret-key")).unsafeRun(())
```

## Context

`Context[T]` organizes config-driven nodes into modules:

```scala
case class DbConfig(url: String, timeout: Int)

object DataPipeline extends Context[DbConfig] {

  val fetch = Context.Extract[Unit, String] { cfg => _ =>
    s"Connected to ${cfg.url} with timeout ${cfg.timeout}s"
  }

  val save = Context.Load[String, Unit] { cfg => data =>
    println(s"Saving to ${cfg.url}: $data")
  }

  val pipeline = fetch ~> save
}

DataPipeline.pipeline.provide(DbConfig("jdbc:pg", 5000)).unsafeRun(())
```

!!! note "Scala 2"
    Use explicit types for better inference:
    ```scala
    Transform.requires[Config, String, String] { cfg => input =>
      cfg.key + input
    }
    ```
    In Scala 3, the preferred syntax is:
    ```scala
    Transform[String, String].requires[Config] { cfg => input =>
      cfg.key + input
    }
    ```
