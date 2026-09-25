---
api:
  - sig: ".lineage(name, inputs, outputs, ...)"
  - sig: ".toDot"
  - sig: ".toMermaid"
  - sig: ".toJson"
  - sig: ".lineageName(name)"
  - sig: ".lineageInputs(inputs*)"
  - sig: ".lineageOutputs(outputs*)"
  - sig: ".withLineage(lineage)"
---

# Diagrams

`.toDot` and `.toMermaid` render any etl4s pipeline as a diagram, in two modes:

- A single `Node` draws its structure - leaf names and in/out types, straight from
  how you composed it.
- A `Seq` of `.lineage`-annotated nodes draws the dataflow you declared -
  datasources, schedules, cross-pipeline dependencies.

Either mode works the same on Reader-wrapped nodes (config-aware, from
`.requires`) - they carry lineage just like plain `Node`s.

## Structure of a single pipeline

Any composed pipeline can draw itself - the same view `.stages` lists. Take the
fan-out / fan-in pipeline with a load step:

```scala
import etl4s._

val extract5 = Node(5)
val double   = Node[Int, Int](_ * 2)
val triple   = Node[Int, Int](_ * 3)
val combine  = Node[(Int, Int), Int] { case (a, b) => a + b }
val saveToDb = Node[Int, Unit](n => println(s"saved $n"))

val pipeline =
     extract5 ~> (double & triple) ~> combine ~> saveToDb

pipeline.toDot
```

<div class="diagram">
<svg width="689pt" height="98pt" viewBox="0.00 0.00 689.00 98.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 94)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="none" stroke="currentColor" cx="105.61" cy="-45" rx="40.6" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="105.61" y="-39.95" font-family="Times,serif" font-size="14.00">extract5</text>
</g>
<g id="node4" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="201.54" cy="-45" rx="4.32" ry="4.32"/>
</g>
<g id="edge3" class="edge">
<path fill="none" stroke="currentColor" d="M146.45,-45C160.19,-45 174.78,-45 185.33,-45"/>
<polygon fill="currentColor" stroke="currentColor" points="185.26,-48.5 195.26,-45 185.26,-41.5 185.26,-48.5"/>
<text xml:space="preserve" text-anchor="middle" x="171.72" y="-48.2" font-family="Times,serif" font-size="14.00">Int</text>
</g>
<g id="node2" class="node">
<ellipse fill="none" stroke="currentColor" cx="292.85" cy="-72" rx="36" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="292.85" y="-66.95" font-family="Times,serif" font-size="14.00">double</text>
</g>
<g id="node5" class="node">
<ellipse fill="none" stroke="currentColor" cx="423.01" cy="-45" rx="43.16" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="423.01" y="-39.95" font-family="Times,serif" font-size="14.00">combine</text>
</g>
<g id="edge4" class="edge">
<path fill="none" stroke="currentColor" d="M326.53,-65.12C340.61,-62.15 357.38,-58.62 372.91,-55.34"/>
<polygon fill="currentColor" stroke="currentColor" points="373.4,-58.82 382.46,-53.33 371.96,-51.97 373.4,-58.82"/>
<text xml:space="preserve" text-anchor="middle" x="354.35" y="-63.32" font-family="Times,serif" font-size="14.00">Int</text>
</g>
<g id="node3" class="node">
<ellipse fill="none" stroke="currentColor" cx="292.85" cy="-18" rx="30.37" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="292.85" y="-12.95" font-family="Times,serif" font-size="14.00">triple</text>
</g>
<g id="edge5" class="edge">
<path fill="none" stroke="currentColor" d="M321.98,-23.93C336.85,-27.06 355.54,-30.99 372.72,-34.62"/>
<polygon fill="currentColor" stroke="currentColor" points="371.76,-37.99 382.26,-36.63 373.2,-31.14 371.76,-37.99"/>
<text xml:space="preserve" text-anchor="middle" x="354.35" y="-35.51" font-family="Times,serif" font-size="14.00">Int</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M205.93,-46.03C213.46,-48.3 232.34,-54.01 250.49,-59.5"/>
<polygon fill="currentColor" stroke="currentColor" points="249.26,-62.78 259.85,-62.32 251.29,-56.08 249.26,-62.78"/>
<text xml:space="preserve" text-anchor="middle" x="231.36" y="-58.49" font-family="Times,serif" font-size="14.00">Int</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" d="M205.38,-42.71C209.3,-39.67 216.7,-34.36 223.86,-31.5 232.6,-28.01 242.34,-25.39 251.66,-23.44"/>
<polygon fill="currentColor" stroke="currentColor" points="252.05,-26.92 261.24,-21.65 250.77,-20.04 252.05,-26.92"/>
<text xml:space="preserve" text-anchor="middle" x="231.36" y="-34.7" font-family="Times,serif" font-size="14.00">Int</text>
</g>
<g id="node6" class="node">
<ellipse fill="none" stroke="currentColor" cx="565.97" cy="-45" rx="48.79" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="565.97" y="-39.95" font-family="Times,serif" font-size="14.00">saveToDb</text>
</g>
<g id="edge6" class="edge">
<path fill="none" stroke="currentColor" d="M466.3,-45C478.6,-45 492.26,-45 505.4,-45"/>
<polygon fill="currentColor" stroke="currentColor" points="505.26,-48.5 515.26,-45 505.26,-41.5 505.26,-48.5"/>
<text xml:space="preserve" text-anchor="middle" x="491.68" y="-48.2" font-family="Times,serif" font-size="14.00">Int</text>
</g>
<g id="node8" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="677.64" cy="-45" rx="2.88" ry="2.88"/>
</g>
<g id="edge8" class="edge">
<path fill="none" stroke="currentColor" d="M615.07,-45C632.55,-45 650.96,-45 663.05,-45"/>
<polygon fill="currentColor" stroke="currentColor" points="662.91,-48.5 672.91,-45 662.91,-41.5 662.91,-48.5"/>
<text xml:space="preserve" text-anchor="middle" x="644.76" y="-48.2" font-family="Times,serif" font-size="14.00">Unit</text>
</g>
<g id="node7" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="2.88" cy="-45" rx="2.88" ry="2.88"/>
</g>
<g id="edge7" class="edge">
<path fill="none" stroke="currentColor" d="M5.98,-45C12.44,-45 32.81,-45 53.36,-45"/>
<polygon fill="currentColor" stroke="currentColor" points="53.2,-48.5 63.2,-45 53.2,-41.5 53.2,-48.5"/>
<text xml:space="preserve" text-anchor="middle" x="35.38" y="-48.2" font-family="Times,serif" font-size="14.00">Any</text>
</g>
</g>
</svg>
</div>

Two options:

- `showTypes = false` drops the type labels on the edges.
- `direction` sets the layout: `Direction.LR` (default), `TB`, `RL`, `BT`.

```scala
pipeline.toDot(showTypes = false)
pipeline.toMermaid(direction = Direction.TB)
```

See [Your First Pipeline](first-pipeline.md#inspect-the-structure) for the `.stages` list
behind the same view.

## Lineage of a dataflow

When you want datasources, schedules, and cross-pipeline dependencies, attach lineage
metadata with `.lineage`, then call `.toDot`, `.toMermaid` or `.toJson` on a `Seq` of the
annotated nodes.

## Quick Start

```scala
import etl4s._

val A = Node[String, String](identity)
  .lineage(
    name = "A",
    inputs = List("s1", "s2"),
    outputs = List("s3"), 
    schedule = "0 */2 * * *"
  )

val B = Node[String, String](identity)
  .lineage(
    name = "B",
    inputs = List("s3"),
    outputs = List("s4", "s5")
  )
```

Export to JSON, DOT (Graphviz), or Mermaid:

```scala
Seq(A, B).toJson
Seq(A, B).toDot
Seq(A, B).toMermaid
```

## Visualization

### DOT

Generate DOT graphs for Graphviz:

```scala
Seq(A, B).toDot
```

<p align="center">
  <img src="https://raw.githubusercontent.com/mattlianje/etl4s/master/pix/graphviz-example.svg" width="500">
</p>

### Mermaid

```scala
Seq(A, B).toMermaid
```

```mermaid
graph LR
    classDef pipeline fill:#e1f5fe,stroke:#01579b,stroke-width:2px,color:#000
    classDef dataSource fill:#f3e5f5,stroke:#4a148c,stroke-width:2px,color:#000

    A["A<br/>(0 */2 * * *)"]
    B["B"]
    s1(["s1"])
    s2(["s2"])
    s3(["s3"])
    s4(["s4"])
    s5(["s5"])

    s1 --> A
    s2 --> A
    A --> s3
    s3 --> B
    B --> s4
    B --> s5
    A -.-> B
    linkStyle 6 stroke:#ff6b35,stroke-width:2px

    class A pipeline
    class B pipeline
    class s1 dataSource
    class s2 dataSource
    class s3 dataSource
    class s4 dataSource
    class s5 dataSource
```

Orange dotted arrows show inferred dependencies.

### JSON

```scala
Seq(A, B).toJson
```

The JSON has three top-level keys (all lowercase):

- `pipelines`: array of pipeline objects (with their `inputs`, `outputs`,
  `upstream_pipelines`, `schedule`, `description`, `group`, `tags`, `links`, ...)
- `datasources`: array of data source names
- `clusters`: array of cluster objects

## Lineage Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `name` | Unique identifier | required |
| `inputs` | Input data sources | empty |
| `outputs` | Output data sources | empty |
| `upstreams` | Explicit dependencies (`Node`, `Reader`, or `String`) | empty |
| `schedule` | Human-readable schedule, e.g. `0 */2 * * *` | none |
| `cluster` | Group name for related pipelines | none |
| `description` | Free-text description | `""` |
| `group` | Logical grouping label | `""` |
| `tags` | `List[String]` of arbitrary tags | empty |
| `links` | `Map[String, String]` of label -> URL | empty |

`.lineage(...)` works the same on a `Reader[T, Node]` (config-aware node) as it
does on a plain `Node`.

### Low-level setters

For attaching metadata incrementally there are also individual setters:
`.lineageName(name)`, `.lineageInputs(inputs*)`, `.lineageOutputs(outputs*)`, and
`.withLineage(lineage)` (attach a fully-built `Lineage`). These are available on
both `Node` and `Reader`.

Unlike `.toDot` / `.toMermaid` (which dispatch on single-node structure vs. `Seq`
dataflow, as described at the top), `.toJson` always emits the lineage metadata -
on a single node or a `Seq`.

## Explicit Upstreams

Use `upstreams` for non-data dependencies:

If you add a node `C`
```scala
val C = Node[String, String](identity)
  .lineage("C", upstreams = List(A, B))

```

Then do:
```scala
Seq(A, B, C).toDot
```

<p align="center">
  <img src="https://raw.githubusercontent.com/mattlianje/etl4s/master/pix/graphviz-dependencies-example.svg" width="500">
</p>

Note how `C` has an orange upstream dependency to `A` and `B` despite not having as inputs their outputs.


## Clusters

Group related pipelines:

```scala
val B = Node[String, String](identity)
  .lineage(
    name = "B",
    inputs = List("s3"),
    outputs = List("s4", "s5"),
    cluster = "Y"
  )

val C = Node[String, String](identity)
  .lineage(
    name = "C",
    upstreams = List(A, B),
    cluster = "Y"
  )

Seq(A, B, C).toDot
```

<p align="center">
  <img src="https://raw.githubusercontent.com/mattlianje/etl4s/master/pix/graphviz-cluster-example.svg" width="500">
</p>

