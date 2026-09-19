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

val p =
     extract5 ~> timesTwo ~> consoleLoad

p.unsafeRun()
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

val p =
     extract5 ~> (double & triple) ~> combine

p.unsafeRun() /* 25 */
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

Take the fan-out / fan-in pipeline from earlier and add a load step that writes the result:

```scala
val saveToDb = Node[Int, Unit](n => println(s"saved $n"))

val p =
     extract5 ~> (double & triple) ~> combine ~> saveToDb
```

`.stages` gives you the steps in execution order, each with its val-name and in/out types:

```scala
p.stages.foreach(s => println(s"${s.name}: ${s.in} => ${s.out}"))

/*
extract5: Any => Int
double: Int => Int
triple: Int => Int
combine: Tuple2[Int, Int] => Int
saveToDb: Int => Unit
*/
```

`.toDot` renders a Graphviz graph, and `.toMermaid` a Mermaid one.

```scala
pipeline.toDot
```

Feed that to Graphviz and you get:

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

Both take options: `showTypes = false` drops the type labels on the edges, and `direction`
changes the layout (`Direction.LR`, `TB`, `RL`, `BT`):

```scala
p.toDot(showTypes = false)
p.toMermaid(direction = Direction.TB)
```

That sums it up - you've seen stitching, config-driven nodes, and how to inspect a pipeline
... etl4s does have more operators and features ... but you've 90% of what there is to see.
