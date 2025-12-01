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
  transition: transform 0.2s ease;
}

.intro-header img:hover {
  transform: scale(1.05);
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
  gap: 1.5rem;
  justify-content: center;
  margin-bottom: 2rem;
  flex-wrap: wrap;
}

.intro-buttons a {
  padding: 0.4rem 0;
  text-decoration: none !important;
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--md-primary-fg-color);
  border-bottom: 1.5px solid transparent;
  transition: border-color 0.2s ease;
}

.intro-buttons a.btn-primary {
  color: var(--md-primary-fg-color);
  border-bottom: 1.5px solid var(--md-primary-fg-color);
}

.intro-buttons a.btn-secondary {
  color: var(--md-primary-fg-color);
  opacity: 0.8;
}

.intro-buttons a:hover {
  border-bottom-color: var(--md-primary-fg-color);
}

/* Zen dividers */
.md-typeset hr {
  margin: 1.25rem auto;
  border: none;
  height: 1px;
  background: var(--md-default-fg-color--lightest);
  max-width: 120px;
}

/* Feature grid - Elm style */
.feature-grid {
  margin: 2rem 0;
}

.feature-row {
  display: flex;
  gap: 2rem;
  align-items: center;
  margin: 2.5rem 0;
}

.feature-row.reverse {
  flex-direction: row-reverse;
}

.feature-text {
  flex: 1.2;
}

.feature-text h3 {
  font-size: 0.85rem;
  font-weight: 600;
  margin: 0 0 0.4rem 0;
  color: var(--md-default-fg-color) !important;
  opacity: 1 !important;
}

.feature-text p {
  font-size: 0.75rem;
  line-height: 1.5;
  margin: 0;
  opacity: 0.9;
}

.feature-visual {
  flex: 0.8;
}

.feature-visual pre {
  margin: 0 !important;
  font-size: 0.8rem !important;
}

.feature-visual.quote blockquote {
  margin: 0;
  padding: 1rem 1.25rem;
  background: none;
  border: none;
  font-style: italic;
  font-size: 0.9rem;
  line-height: 1.5;
  position: relative;
}

.feature-visual.quote blockquote::before {
  content: "\201D";
  position: absolute;
  top: -1.5rem;
  left: -0.5rem;
  font-size: 8rem;
  font-family: Georgia, serif;
  color: var(--md-primary-fg-color);
  opacity: 0.2;
  line-height: 1;
  pointer-events: none;
  z-index: 0;
}

.feature-visual.quote blockquote > * {
  position: relative;
  z-index: 1;
}

.feature-visual.quote cite {
  display: block;
  margin-top: 0.75rem;
  font-size: 0.8rem;
  opacity: 0.7;
  font-style: normal;
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

  .feature-row,
  .feature-row.reverse {
    flex-direction: column;
    gap: 1rem;
  }

  .feature-text,
  .feature-visual {
    width: 100%;
  }
}
</style>

<div class="intro-header">
  <img src="assets/etl4s-logo.png" alt="etl4s" />
  <h1>etl4s</h1>
  <p style="opacity: 0.6; font-size: 0.85rem; margin: 0.5rem 0 1.5rem 0;">Powerful, whiteboard-style ETL</p>
  <div class="intro-buttons">
    <a href="installation/" class="btn-primary">Get Started</a>
    <a href="https://scastie.scala-lang.org/mattlianje/1280QhQ5RWODgizeXOIsXA/5" target="_blank" class="btn-secondary">Try Online</a>
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
      source["source<br><sub>Int</sub>"] --> increment["increment<br><sub>Int → Int</sub>"]
      increment --> sink["sink<br><sub>Int → Unit</sub>"]
    ```

=== "Telemetry"

    ```scala
    import etl4s._

    val process = Transform[List[String], Int] { data =>
      Tel.withSpan("processing") {
        Tel.addCounter("items", data.size)
        data.map(_.length).sum
      }
    }

    // Dev: no-ops (zero cost)
    process.unsafeRun(data)

    // Prod: plug in your backend
    implicit val tel: Etl4sTelemetry = MyOtelProvider()
    process.unsafeRun(data)
    ```

=== "Tracing"

    ```scala
    import etl4s._

    val process = Transform[String, Int] { s =>
      Trace.log("Processing")
      if (s.isEmpty) Trace.error("Empty input!")
      s.length
    }

    val trace = process.unsafeRunTrace("hello")
    // trace.result         -> 5
    // trace.logs           -> List("Processing")
    // trace.errors         -> List()
    // trace.timeElapsedMillis
    ```

---

<div class="feature-grid">

<div class="feature-row">
<div class="feature-text">
<h3>Single file. Zero dependencies.</h3>
<p>A header-file lib (not a framework) that lets you structure code like whiteboard diagrams. Chain with <code>~></code>, parallelize with <code>&</code>, inject config with <code>.requires</code>. Works anywhere: scripts, Spark, Flink, your server.</p>
</div>
<div class="feature-visual">
```scala
// That's it. One import.
import etl4s._

val pipeline =
  extract ~> transform ~> load
```
</div>
</div>

<div class="feature-row reverse">
<div class="feature-text">
<h3>Pipelines as values.</h3>
<p>Lazy, reified, composable. Pass them around, test them in isolation, generate diagrams from them. Teams share pipelines like Lego bricks. Refactoring is safe because types enforce the boundaries.</p>
</div>
<div class="feature-visual quote">
<blockquote>
(~>) is just *chef's kiss*. There are so many synergies here, haven't pushed for something this hard in a while.
<cite>— Sr Engineering Manager, Instacart</cite>
</blockquote>
</div>
</div>

<div class="feature-row">
<div class="feature-text">
<h3>For engineers & teams.</h3>
<p>Write <code>e ~> t ~> l</code>. Types must match or it won't compile. One core abstraction: <code>Node[In, Out]</code>. Structure survives people leaving. New hires read <code>e ~> (t1 & t2) ~> l</code> and get it. Auto-generated diagrams document your pipelines.</p>
</div>
<div class="feature-visual">
```scala
val e = Extract(1)
val t = Transform[Int, String](_.toString)
val l = Load[String, Unit](println)

e ~> t ~> l  // ✓ compiles
e ~> l       // ✗ won't compile
```
</div>
</div>

<div class="feature-row reverse">
<div class="feature-text">
<h3>Under the hood:</h3>
<p>A lightweight effect system with one core <code>Node[-In, +Out]</code> abstraction. The <code>~></code> operator infers and merges environments seamlessly. <a href="faq/#how-it-works">Details in FAQ</a>.</p>
</div>
<div class="feature-visual quote">
<blockquote>
Seems to provide most of the advantages of full blown "effect systems" without the complexities, and awkward monad syntax!
<cite>— u/RiceBroad4552</cite>
</blockquote>
</div>
</div>

</div>

---

<p style="text-align: center; opacity: 0.8; margin: 2rem 0;">
  Battle-tested at <a href="https://www.instacart.com/">Instacart</a> 🥕
</p>

---

## Get started

- [Installation](installation.md)
- [First Pipeline](first-pipeline.md)
- [Core Concepts](core-concepts.md)
- [Examples](examples.md)

<div style="margin-bottom: 4rem;"></div>
