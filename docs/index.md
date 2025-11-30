---
hide:
  - toc
---

<style>
/* Keep sidebar but hide TOC on index page */
.md-sidebar--secondary,
.md-sidebar--primary {
  display: none;
}

.md-content__inner {
  max-width: 700px !important;
  padding: 0 1rem !important;
  margin: 0 auto !important;
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
  font-weight: 300;
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

/* Zen dividers */
.md-typeset hr {
  margin: 1.25rem auto;
  border: none;
  height: 1px;
  background: var(--md-default-fg-color--lightest);
  max-width: 120px;
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
  <p>Powerful, whiteboard-style ETL</p>
  <div class="intro-buttons">
    <a href="installation/" class="btn-primary">Get Started</a>
    <a href="https://scastie.scala-lang.org/mattlianje/1280QhQ5RWODgizeXOIsXA/5" target="_blank" class="btn-secondary">Try in Scastie</a>
    <a href="https://github.com/mattlianje/etl4s" target="_blank" class="btn-secondary">GitHub</a>
  </div>
</div>

=== "Chain"

    ```scala
    import etl4s._

    val extract = Extract(List(1, 2, 3, 4, 5))
    val double  = Transform[List[Int], List[Int]](_.map(_ * 2))
    val sum     = Transform[List[Int], Int](_.sum)
    val print   = Load[Int, Unit](n => println(s"Result: $n"))

    val pipeline = extract ~> double ~> sum ~> print

    pipeline.unsafeRun(())
    // Result: 30
    ```

=== "Parallel"

    ```scala
    import etl4s._

    val extract = Extract(100)
    val half    = Transform[Int, Int](_ / 2)
    val double  = Transform[Int, Int](_ * 2)
    val format  = Transform[(Int, Int), String] { case (h, d) =>
      s"half=$h, double=$d"
    }

    val pipeline = extract ~> (half & double) ~> format

    pipeline.unsafeRun(())
    // "half=50, double=200"
    ```

=== "Config"

    ```scala
    import etl4s._

    case class DbConfig(host: String, port: Int)

    val extract = Extract(List("a", "b", "c"))
    val save = Load[List[String], Unit].requires[DbConfig] { db => data =>
      println(s"Saving ${data.size} rows to ${db.host}:${db.port}")
    }

    val pipeline = extract ~> save

    pipeline.provide(DbConfig("localhost", 5432)).unsafeRun(())
    // Saving 3 rows to localhost:5432
    ```

=== "Diagram"

    ```scala
    import etl4s._

    val e = Extract(1).named("source")
    val t = Transform[Int, Int](_ + 1).named("increment")
    val l = Load[Int, Unit](println).named("sink")

    val pipeline = e ~> t ~> l

    println(pipeline.toMermaid)
    ```

    ```mermaid
    graph LR
      source["source"] --> increment["increment"]
      increment["increment"] --> sink["sink"]
    ```

---

**Single file. Zero dependencies. Pure Scala.**

A library, not a framework. Without discipline, data teams drown in sprawling, coupled codebases. etl4s imposes one constraint: `extract ~> transform ~> load`. Chain with `~>`, parallelize with `&`, inject config with `.requires`. Works anywhere: scripts, Spark, Flink, your server.

---

**Pipelines are values.**

Lazy, reified, composable. Pass them around, test them in isolation, generate diagrams from them. Teams share pipelines like Lego bricks. Refactoring is safe because types enforce the boundaries.

---

**For engineers and teams:**

Write `extract ~> transform ~> load`. Types must match or it won't compile. One core abstraction: `Node[In, Out]`. Structure survives people leaving. New hires read `e ~> (t1 & t2) ~> l` and get it. Auto-generated diagrams document your pipelines.

---

**Under the hood:**

A lightweight effect system built on a Reader monad with an overloaded `~>` that infers and merges environments. No fiber runtime, no magic. [Details in FAQ](faq.md#how-it-works).

---

<p style="text-align: center; opacity: 0.8; margin: 2rem 0;">
  Battle-tested at <a href="https://www.instacart.com/">Instacart</a> 🥕
</p>

---

## Get started

- **[Installation](installation.md)**: Add to your project
- **[Your First Pipeline](first-pipeline.md)**: Build something in 5 minutes
- **[Core Concepts](core-concepts.md)**: Node, `~>`, `&`, `.requires`
- **[Examples](examples.md)**: Common patterns
