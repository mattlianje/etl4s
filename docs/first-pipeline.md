# Your First Pipeline

In etl4s, everything is either:

- A `Node[-In, +Out]`
- A `Node` wrapped in a Reader monad (`Reader[Cfg, Node[In, Out]]`) 
     - `Cfg` is the configuration type needed to run the node


Nodes are simple lambdas from `In => Out`
```scala
import etl4s._

val double = Node[Int, Int](_ * 2)

double(5) /* 10 */
```

You can run them like functions or be more deliberate with `.unsafeRun(In)` ... just
a matter of taste.

Create new nodes by chaining existing ones together:
```scala

val pipeline = 
     double ~> double

pipeline(5) /* 20 */
```

Here is a more substantive example:
```scala
val extract5    = Node(5)
val timesTwo    = Node[Int, Int](_ * 2)
val consoleLoad = Node[Int, Unit](x => println(s"Result: $x"))

val pipeline =
     extract5 ~> timesTwo ~> consoleLoad

pipeline.unsafeRun()
```

This will give:
```
Result: 10
```

You can use other operators like `&` to fan out and stitch your graphs

```scala
val double  = Node[Int, Int](_ * 2)
val triple  = Node[Int, Int](_ * 3)
val combine = Node[(Int, Int), Int] { case (a, b) => a + b }

val pipeline =
     extract5 ~> (double & triple) ~> combine

pipeline.unsafeRun() /* 25 */
```

One of the key benefits of etl4s is that you can separate configuration (the "knobs to
turn") from your actual flow of data.


```scala
val YEAR = 2025

val loadData = Node[Any, String] { _ =>
  println(s"Loading $YEAR data")
  "TEST DATA"
}

loadData.unsafeRun()
```

Prints `Loading 2025 data` and returns `"TEST DATA"`.

That works for one value, but it doesn't compose: every node that reaches for `year` is
an invisible, untyped dependency.

etl4s makes the knob an explicit input instead, declare it
with `.requires`, then `.provide` it once at the edge:

```scala
case class Config(year: Int)

val loadData = Node[Unit, String].requires[Config] { 
    config => _ => s"Loading ${config.year} data"
}

loadData.provide(Config(2025)).unsafeRun(()) /* "Loading 2025 data" */
```

`.requires` turns the node into a `Reader[Config, Node[...]]`, and config-aware and plain nodes
compose together with the same `~>`

## Inspect the structure

A pipeline is a value you can look at *before* running it.

Every `Node` carries its own shape, its in/out types and the enclosing `val` name,
both captured at compile time by a small macro, so you can dump its stages or render it as a diagram:

Take the fan-out / fan-in pipeline from earlier:

```scala
val pipeline =
     extract5 ~> (double & triple) ~> combine
```

`.stages` gives you the steps in execution order, each with its val-name and in/out types:

```scala
pipeline.stages.foreach(s => println(s"${s.name}: ${s.in} => ${s.out}"))

/*
extract5: Any => Int
double:   Int => Int
triple:   Int => Int
combine:  (Int, Int) => Int
*/
```

`.toDot` renders a Graphviz graph, and `.toMermaid` a Mermaid one. The fan-out and fan-in show
up as branches:

```scala
pipeline.toDot

/*
digraph G {
  rankdir=LR;
  n0 [label="extract5\nAny => Int"];
  n1 [label="double\nInt => Int"];
  n2 [label="triple\nInt => Int"];
  n0 -> n1;
  n0 -> n2;
  n3 [label="combine\n(Int, Int) => Int"];
  n1 -> n3;
  n2 -> n3;
}
*/
```

Feed that to Graphviz and you get:

<div class="diagram">
<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="434pt" height="144pt" viewBox="0.00 0.00 434.00 144.00">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 139.83)" fill="currentColor">
<ellipse fill="none" stroke="currentColor" cx="55.47" cy="-67.42" rx="55.47" ry="29.42"/>
<text xml:space="preserve" text-anchor="middle" x="55.47" y="-71.62" font-family="Times,serif" font-size="14.00">extract5</text>
<text xml:space="preserve" text-anchor="middle" x="55.47" y="-54.82" font-family="Times,serif" font-size="14.00">Any =&gt; Int</text>
<ellipse fill="none" stroke="currentColor" cx="196.37" cy="-106.42" rx="49.42" ry="29.42"/>
<text xml:space="preserve" text-anchor="middle" x="196.37" y="-110.62" font-family="Times,serif" font-size="14.00">double</text>
<text xml:space="preserve" text-anchor="middle" x="196.37" y="-93.82" font-family="Times,serif" font-size="14.00">Int =&gt; Int</text>
<path fill="none" stroke="currentColor" d="M105.05,-81.06C116.44,-84.25 128.64,-87.68 140.29,-90.95"/>
<polygon fill="currentColor" stroke="currentColor" points="139.2,-94.28 149.78,-93.61 141.09,-87.54 139.2,-94.28"/>
<ellipse fill="none" stroke="currentColor" cx="196.37" cy="-29.42" rx="49.42" ry="29.42"/>
<text xml:space="preserve" text-anchor="middle" x="196.37" y="-33.62" font-family="Times,serif" font-size="14.00">triple</text>
<text xml:space="preserve" text-anchor="middle" x="196.37" y="-16.82" font-family="Times,serif" font-size="14.00">Int =&gt; Int</text>
<path fill="none" stroke="currentColor" d="M105.44,-54.02C116.69,-50.94 128.72,-47.65 140.21,-44.51"/>
<polygon fill="currentColor" stroke="currentColor" points="140.83,-47.97 149.55,-41.95 138.98,-41.21 140.83,-47.97"/>
<ellipse fill="none" stroke="currentColor" cx="353.76" cy="-67.42" rx="71.96" ry="29.42"/>
<text xml:space="preserve" text-anchor="middle" x="353.76" y="-71.62" font-family="Times,serif" font-size="14.00">combine</text>
<text xml:space="preserve" text-anchor="middle" x="353.76" y="-54.82" font-family="Times,serif" font-size="14.00">(Int, Int) =&gt; Int</text>
<path fill="none" stroke="currentColor" d="M242.32,-95.14C254.36,-92.11 267.69,-88.77 280.8,-85.48"/>
<polygon fill="currentColor" stroke="currentColor" points="281.41,-88.93 290.26,-83.1 279.71,-82.14 281.41,-88.93"/>
<path fill="none" stroke="currentColor" d="M242.73,-40.51C254.44,-43.37 267.34,-46.53 280.07,-49.64"/>
<polygon fill="currentColor" stroke="currentColor" points="279.15,-53.02 289.69,-51.99 280.81,-46.22 279.15,-53.02"/>
</g>
</svg>
</div>

Both take options: `showTypes = false` drops the `In => Out` labels, and `direction` changes
the layout (`Direction.LR`, `TB`, `RL`, `BT`):

```scala
pipeline.toDot(showTypes = false)
pipeline.toMermaid(direction = Direction.TB)
```

That sums it up - you've seen stitching, config-driven nodes, and how to inspect a pipeline
... etl4s does have more operators and features ... but you've 90% of what there is to see.
