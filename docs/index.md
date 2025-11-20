---
hide:
  - toc
---

<style>
/* Keep sidebar but hide TOC on index page */
.md-sidebar--secondary {
  display: none;
}

.md-content__inner {
  max-width: 700px !important;
  padding: 0 1rem !important;
}

.intro-header {
  text-align: center;
  padding: 2rem 0 1rem 0;
}

.intro-header img {
  height: 64px;
  margin-bottom: 1rem;
}

.intro-header h1 {
  font-size: 2.5rem;
  margin: 0.5rem 0;
  border: none !important;
  padding: 0 !important;
}

.intro-header p {
  font-size: 1.1rem;
  margin: 0.5rem 0 1.5rem 0;
  opacity: 0.9;
}

.intro-buttons {
  display: flex;
  gap: 0.75rem;
  justify-content: center;
  margin-bottom: 2rem;
  flex-wrap: wrap;
}

.intro-buttons a {
  padding: 0.5rem 1rem;
  border-radius: 0.3rem;
  text-decoration: none !important;
  font-size: 0.85rem;
  font-weight: 500;
}

.intro-buttons a.btn-primary {
  background: var(--md-primary-fg-color);
  color: var(--md-primary-bg-color);
}

.intro-buttons a.btn-secondary {
  border: 1px solid var(--md-primary-fg-color);
  color: var(--md-primary-fg-color);
}

.intro-buttons a:hover {
  opacity: 0.85;
}

/* Mobile adjustments */
@media (max-width: 768px) {
  .intro-header h1 {
    font-size: 2rem;
  }
  
  .intro-header p {
    font-size: 1rem;
  }
  
  .intro-buttons {
    flex-direction: column;
    align-items: stretch;
    max-width: 300px;
    margin-left: auto;
    margin-right: auto;
  }
  
  .intro-buttons a {
    text-align: center;
  }
}
</style>

<div class="intro-header">
  <img src="assets/etl4s-logo.png" alt="etl4s" />
  <h1>etl4s</h1>
  <p>Whiteboard-style ETL for Scala</p>
  <div class="intro-buttons">
    <a href="installation/" class="btn-primary">Get Started</a>
    <a href="https://scastie.scala-lang.org/mattlianje/1280QhQ5RWODgizeXOIsXA/5" target="_blank" class="btn-secondary">Try in Scastie</a>
    <a href="https://github.com/mattlianje/etl4s" target="_blank" class="btn-secondary">GitHub</a>
  </div>
</div>

```scala
import etl4s._

/* Define your building blocks */
val fiveExtract = Extract(5)
val timesTwo    = Transform[Int, Int](_ * 2)
val exclaim     = Transform[Int, String](n => s"$n!")
val consoleLoad = Load[String, Unit](println(_))

/* Add config with .requires */
val dbLoad = Load[String, Unit].requires[String] { dbType => s =>
  println(s"Saving to $dbType DB: $s")
}

/* Stitch your pipeline */
val pipeline =
  fiveExtract ~> timesTwo ~> exclaim ~> (consoleLoad & dbLoad)

/* Provide config, then run */
pipeline.provide("sqlite").unsafeRun(())
```

## What

**etl4s** is a single-file, zero-dependency Scala library for expressing code as whiteboard-style pipelines. Chain operations with `~>`, parallelize with `&`, inject dependencies with `.requires`.

## Why

Ultimately, these nodes and pipelines are just reifications of functions and values with a few extra niceties. But without imposed discipline,
data-orgs drive themselves to their knees with sprawling, framework coupled analytical codebases.

**etl4s** is a lightweight DSL that enforces type-safety, makes dependencies explicit, and lets you build with pure functions.

## What it does

Makes structure explicit. Makes dependencies visible. Makes composition safe.

**For engineers:**  
You write `extract ~> transform ~> load`. That's the pipeline. Inject dependencies with `.requires[DbConfig]`.

- **Already a pure-fp zealot?** etl4s is an ultralight effect system with no fiber runtime. Easy to vendor or extend since there's just one core abstraction: `Node[In, Out]`.
- **New to functional programming?** etl4s makes writing and refactoring code feel like snapping Legos together. No category theory jargon required (though you can ease into it).

**For managers:**  
Impose structure on analytical code that survives people coming and going. Engineers ramp up in days because structure is in the code, not someone's head. New hires (and LLM's) read `e ~> (t1 & t2) ~> l` and just "get it".

You get compile-time generated diagrams of all pipelines for free. When people leave, their work stays readable and modular.

## Is it a framework?

No - and never will be. It's an ultralight library that doesn't impose a worldview. Try it zero-cost on one pipeline today.

## Where to use

Anywhere: local scripts, web servers, alongside any framework like Spark or Flink.

## Production ready?

Yes - it powers the World's grocery deliveries at [Instacart](https://www.instacart.com/).

## How it works

**What is `~>`**

`~>` connects pipeline stages. Its an overloaded symbolic operator that works with plain nodes (`Node[In, Out]`) or nodes that need config (`Reader[Env, Node[In, Out]]`).

Mix them freely - the operator figures out what environment is needed. If two stages need different configs, it automatically merges them (as long as the config types are compatible).

**Type safety at compile time**  
`~>` connects nodes. Types must match or it won't compile.

**Config dependencies made visible**  
Use `.requires[DbConfig]` on any node. Pass it with `.provide(config)` at call site. No globals. No guessing what a pipeline needs.

**Pipelines as values**  
Every pipeline is a `Node[In, Out]`. Share them, compose them, test them. Snap together with `~>` and `&`.
This ability to share and stitch your pipelines as values is the bedrock of self-serve.

**Metrics in business logic**  
In OLAP, metrics ARE business logic. Sprinkle `Tel` calls in your functions - zero-cost by default, light up in prod with your implementation:

```scala
val process = Transform[List[User], Int] { users =>
  Tel.addCounter("users_processed", users.size)
  users.filter(_.isValid).length
}
```

**Execution visibility**  
Call `.unsafeRunTrace()` to get full execution context - logs, errors, timing. No manual wiring needed:

```scala
val trace = pipeline.unsafeRunTrace(data)
trace.logs                /* everything logged during execution */
trace.errors              /* all errors encountered */
trace.timeElapsedMillis   /* how long it took */
```

Any stage can log with `Trace.log()` or record errors with `Trace.error()`. Downstream stages see upstream issues automatically via `Trace.current` - no passing state through function parameters.

**Automatic lineage**  
Nodes are objects. Attach custom metadata at compile time (impossible with raw functions), then call `.toMermaid` or `.toDot` for diagrams.

## Next steps

**[Installation](installation.md)** - Add to your project

**[Core Concepts](core-concepts.md)** - Node, `~>`, `&`, `.requires`

**[Your First Pipeline](first-pipeline.md)** - Build something in 5 minutes

**[Examples](examples.md)** - Common patterns and recipes

**[API Reference](operators.md)** - All the operators and methods
