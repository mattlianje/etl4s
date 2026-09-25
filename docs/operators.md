# Operators

| Operator | Name | What it does |
|----------|------|--------------|
| `~>` | Chain | `a ~> b` - output of `a` feeds into `b` |
| `&` / `&>` | Fan-out | `a & b` - run both with the **same** input (`&>` runs them concurrently) |
| `*` / `*>` | Product | `a * b` - run on **different** inputs (`*>` runs them concurrently) |
| `>>` | Sequence | `a >> b` - run in order, keep `b`'s result |
| <code>&#124;</code> | Fan-in | <code>a &#124; b</code> - route an `Either` input to the matching branch |
| `+` | Choice | `a + b` - route an `Either` input through independent branches |
| <code>&lt;&#124;&gt;</code> | Fallback | <code>a &lt;&#124;&gt; b</code> - if `a` throws, run `b` on the same input |

## `~>` chain

<div class="diagram">
<svg width="141pt" height="33pt" viewBox="0.00 0.00 188.00 44.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 40)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="none" stroke="currentColor" cx="18" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="18" y="-11.82" font-family="Times,serif" font-size="14.00">a</text>
</g>
<g id="node2" class="node">
<ellipse fill="none" stroke="currentColor" cx="90" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="90" y="-11.82" font-family="Times,serif" font-size="14.00">b</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M36.3,-18C43.59,-18 52.27,-18 60.46,-18"/>
<polygon fill="currentColor" stroke="currentColor" points="60.38,-21.5 70.38,-18 60.38,-14.5 60.38,-21.5"/>
</g>
<g id="node3" class="node">
<ellipse fill="none" stroke="currentColor" cx="162" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="162" y="-11.82" font-family="Times,serif" font-size="14.00">c</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" d="M108.3,-18C115.59,-18 124.27,-18 132.46,-18"/>
<polygon fill="currentColor" stroke="currentColor" points="132.38,-21.5 142.38,-18 132.38,-14.5 132.38,-21.5"/>
</g>
</g>
</svg>
</div>

Output of one node feeds the next, generally the backbone of every pipeline:

```scala
val checkout = 
     parseCart ~> applyTax ~> total
```

## `&` / `&>` fan-out (shared input)

<div class="diagram">
<svg width="126pt" height="74pt" viewBox="0.00 0.00 168.00 98.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 94)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-45" rx="3.24" ry="3.24"/>
</g>
<g id="node3" class="node">
<ellipse fill="none" stroke="currentColor" cx="60.48" cy="-72" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="60.48" y="-65.83" font-family="Times,serif" font-size="14.00">b</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M6.61,-46.16C11.4,-48.5 22.7,-54.02 33.62,-59.36"/>
<polygon fill="currentColor" stroke="currentColor" points="31.88,-62.41 42.4,-63.65 34.95,-56.12 31.88,-62.41"/>
</g>
<g id="node4" class="node">
<ellipse fill="none" stroke="currentColor" cx="60.48" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="60.48" y="-11.82" font-family="Times,serif" font-size="14.00">c</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" d="M6.61,-43.84C11.4,-41.5 22.7,-35.98 33.62,-30.64"/>
<polygon fill="currentColor" stroke="currentColor" points="34.95,-33.88 42.4,-26.35 31.88,-27.59 34.95,-33.88"/>
</g>
<g id="node2" class="node">
<text xml:space="preserve" text-anchor="middle" x="137.11" y="-38.83" font-family="Times,serif" font-size="14.00">(b, c)</text>
</g>
<g id="edge3" class="edge">
<path fill="none" stroke="currentColor" d="M77.67,-66.14C85.35,-63.36 94.81,-59.94 103.87,-56.66"/>
<polygon fill="currentColor" stroke="currentColor" points="104.93,-60 113.14,-53.31 102.54,-53.42 104.93,-60"/>
</g>
<g id="edge4" class="edge">
<path fill="none" stroke="currentColor" d="M77.67,-23.86C85.35,-26.64 94.81,-30.06 103.87,-33.34"/>
<polygon fill="currentColor" stroke="currentColor" points="102.54,-36.58 113.14,-36.69 104.93,-30 102.54,-36.58"/>
</g>
</g>
</svg>
</div>

One input, two nodes, results paired. Swap `&` for `&>` to fan out concurrently under an effect like `Future`.
One user goes in and both cards are built from it:

```scala
val dashboard = 
     fetchUser ~> (profileCard & recentOrders) ~> layout
```

## `*` / `*>` product (different inputs)

<div class="diagram">
<svg width="126pt" height="74pt" viewBox="0.00 0.00 168.00 98.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 94)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-72" rx="3.24" ry="3.24"/>
</g>
<g id="node4" class="node">
<ellipse fill="none" stroke="currentColor" cx="60.48" cy="-72" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="60.48" y="-65.83" font-family="Times,serif" font-size="14.00">b</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M6.9,-72C11.43,-72 21.08,-72 30.89,-72"/>
<polygon fill="currentColor" stroke="currentColor" points="30.67,-75.5 40.67,-72 30.67,-68.5 30.67,-75.5"/>
</g>
<g id="node2" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-18" rx="3.24" ry="3.24"/>
</g>
<g id="node5" class="node">
<ellipse fill="none" stroke="currentColor" cx="60.48" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="60.48" y="-11.82" font-family="Times,serif" font-size="14.00">d</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" d="M6.9,-18C11.43,-18 21.08,-18 30.89,-18"/>
<polygon fill="currentColor" stroke="currentColor" points="30.67,-21.5 40.67,-18 30.67,-14.5 30.67,-21.5"/>
</g>
<g id="node3" class="node">
<text xml:space="preserve" text-anchor="middle" x="137.48" y="-38.83" font-family="Times,serif" font-size="14.00">(b, d)</text>
</g>
<g id="edge3" class="edge">
<path fill="none" stroke="currentColor" d="M77.75,-66.14C85.41,-63.38 94.83,-59.99 103.86,-56.74"/>
<polygon fill="currentColor" stroke="currentColor" points="104.9,-60.09 113.12,-53.41 102.53,-53.5 104.9,-60.09"/>
</g>
<g id="edge4" class="edge">
<path fill="none" stroke="currentColor" d="M77.75,-23.86C85.41,-26.62 94.83,-30.01 103.86,-33.26"/>
<polygon fill="currentColor" stroke="currentColor" points="102.53,-36.5 113.12,-36.59 104.9,-29.91 102.53,-36.5"/>
</g>
</g>
</svg>
</div>

Where `&` broadcasts one input, `*` hands each node its own - `_._1` left, `_._2` right. Swap `*` for `*>` to run the two branches concurrently.
Name goes to `trimName`, age goes to `bumpAge`:

```scala
val register = 
     (trimName * bumpAge) ~> saveAccount
```

## `>>` sequence (keep last)

<div class="diagram">
<svg width="143pt" height="74pt" viewBox="0.00 0.00 190.00 98.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 94)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-45" rx="3.24" ry="3.24"/>
</g>
<g id="node4" class="node">
<ellipse fill="none" stroke="currentColor" cx="60.48" cy="-72" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="60.48" y="-65.83" font-family="Times,serif" font-size="14.00">a</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M6.61,-46.16C11.4,-48.5 22.7,-54.02 33.62,-59.36"/>
<polygon fill="currentColor" stroke="currentColor" points="31.88,-62.41 42.4,-63.65 34.95,-56.12 31.88,-62.41"/>
</g>
<g id="node5" class="node">
<ellipse fill="none" stroke="currentColor" cx="60.48" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="60.48" y="-11.82" font-family="Times,serif" font-size="14.00">b</text>
</g>
<g id="edge3" class="edge">
<path fill="none" stroke="currentColor" d="M6.61,-43.84C11.4,-41.5 22.7,-35.98 33.62,-30.64"/>
<polygon fill="currentColor" stroke="currentColor" points="34.95,-33.88 42.4,-26.35 31.88,-27.59 34.95,-33.88"/>
</g>
<g id="node2" class="node">
<text xml:space="preserve" text-anchor="middle" x="148.36" y="-65.83" font-family="Times,serif" font-size="14.00">discarded</text>
</g>
<g id="node3" class="node">
<text xml:space="preserve" text-anchor="middle" x="148.36" y="-11.82" font-family="Times,serif" font-size="14.00">b&#39;s result</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" stroke-dasharray="5,2" d="M78.86,-72C86.01,-72 94.66,-72 103.36,-72"/>
<polygon fill="currentColor" stroke="currentColor" points="103.2,-75.5 113.2,-72 103.2,-68.5 103.2,-75.5"/>
</g>
<g id="edge4" class="edge">
<path fill="none" stroke="currentColor" d="M78.86,-18C86.52,-18 95.9,-18 105.22,-18"/>
<polygon fill="currentColor" stroke="currentColor" points="104.94,-21.5 114.94,-18 104.94,-14.5 104.94,-21.5"/>
</g>
</g>
</svg>
</div>

Runs the first steps for their side effects, then flows into the real pipeline, keeping its result.
This runs the setups steps in order then flows into the real pipeline:

```scala
val ingest = 
     clearStaging >> warmCache >> (extract ~> transform ~> load)
```

## `|` fan-in (route an `Either` in)

<div class="diagram">
<svg width="142pt" height="74pt" viewBox="0.00 0.00 189.00 98.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 94)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-72" rx="3.24" ry="3.24"/>
</g>
<g id="node4" class="node">
<ellipse fill="none" stroke="currentColor" cx="90.48" cy="-72" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="90.48" y="-65.83" font-family="Times,serif" font-size="14.00">b</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M6.7,-72C14.59,-72 39.99,-72 60.59,-72"/>
<polygon fill="currentColor" stroke="currentColor" points="60.48,-75.5 70.48,-72 60.48,-68.5 60.48,-75.5"/>
<text xml:space="preserve" text-anchor="middle" x="39.48" y="-72.95" font-family="Times,serif" font-size="14.00">Left</text>
</g>
<g id="node2" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-18" rx="3.24" ry="3.24"/>
</g>
<g id="node5" class="node">
<ellipse fill="none" stroke="currentColor" cx="90.48" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="90.48" y="-11.82" font-family="Times,serif" font-size="14.00">c</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" d="M6.7,-18C14.59,-18 39.99,-18 60.59,-18"/>
<polygon fill="currentColor" stroke="currentColor" points="60.48,-21.5 70.48,-18 60.48,-14.5 60.48,-21.5"/>
<text xml:space="preserve" text-anchor="middle" x="39.48" y="-18.95" font-family="Times,serif" font-size="14.00">Right</text>
</g>
<g id="node3" class="node">
<text xml:space="preserve" text-anchor="middle" x="163.48" y="-37.83" font-family="Times,serif" font-size="14.00">out</text>
</g>
<g id="edge3" class="edge">
<path fill="none" stroke="currentColor" d="M107.59,-65.65C115.82,-62.4 126.07,-58.36 135.46,-54.65"/>
<polygon fill="currentColor" stroke="currentColor" points="136.51,-58 144.53,-51.08 133.95,-51.49 136.51,-58"/>
</g>
<g id="edge4" class="edge">
<path fill="none" stroke="currentColor" d="M107.95,-24.03C115.99,-26.97 125.87,-30.59 135.01,-33.94"/>
<polygon fill="currentColor" stroke="currentColor" points="133.59,-37.15 144.19,-37.3 136,-30.58 133.59,-37.15"/>
</g>
</g>
</svg>
</div>

An `Either` input goes left or right, both branches merging to one type.
A legacy id or a uuid comes in, one user comes out:

```scala
val load = 
     (byLegacyId | byUuid) ~> loadAccount
```

## `+` choice

<div class="diagram">
<svg width="146pt" height="74pt" viewBox="0.00 0.00 194.00 98.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 94)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-18" rx="3.24" ry="3.24"/>
</g>
<g id="node5" class="node">
<ellipse fill="none" stroke="currentColor" cx="90.48" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="90.48" y="-11.82" font-family="Times,serif" font-size="14.00">b</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M6.7,-18C14.59,-18 39.99,-18 60.59,-18"/>
<polygon fill="currentColor" stroke="currentColor" points="60.48,-21.5 70.48,-18 60.48,-14.5 60.48,-21.5"/>
<text xml:space="preserve" text-anchor="middle" x="39.48" y="-18.95" font-family="Times,serif" font-size="14.00">Left</text>
</g>
<g id="node2" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-72" rx="3.24" ry="3.24"/>
</g>
<g id="node6" class="node">
<ellipse fill="none" stroke="currentColor" cx="90.48" cy="-72" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="90.48" y="-65.83" font-family="Times,serif" font-size="14.00">c</text>
</g>
<g id="edge3" class="edge">
<path fill="none" stroke="currentColor" d="M6.7,-72C14.59,-72 39.99,-72 60.59,-72"/>
<polygon fill="currentColor" stroke="currentColor" points="60.48,-75.5 70.48,-72 60.48,-68.5 60.48,-75.5"/>
<text xml:space="preserve" text-anchor="middle" x="39.48" y="-72.95" font-family="Times,serif" font-size="14.00">Right</text>
</g>
<g id="node3" class="node">
<text xml:space="preserve" text-anchor="middle" x="165.86" y="-11.82" font-family="Times,serif" font-size="14.00">Left&#39;</text>
</g>
<g id="node4" class="node">
<text xml:space="preserve" text-anchor="middle" x="165.86" y="-65.83" font-family="Times,serif" font-size="14.00">Right&#39;</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" d="M108.87,-18C116.44,-18 125.55,-18 134.2,-18"/>
<polygon fill="currentColor" stroke="currentColor" points="134.01,-21.5 144.01,-18 134.01,-14.5 134.01,-21.5"/>
</g>
<g id="edge4" class="edge">
<path fill="none" stroke="currentColor" d="M108.87,-72C117.21,-72 127.42,-72 136.82,-72"/>
<polygon fill="currentColor" stroke="currentColor" points="136.58,-75.5 146.58,-72 136.58,-68.5 136.58,-75.5"/>
</g>
</g>
</svg>
</div>

Routes an `Either` through independent branches and keeps the `Either` on the way out.
Each txn kind is handled on its own branch, then merged back with `|`

```scala
val settle = 
     classifyTxn ~> (handleRefund + handleCharge) ~> (fileRefund | postCharge)
```

## `<|>` fallback (try, then recover)

<div class="diagram">
<svg width="164pt" height="74pt" viewBox="0.00 0.00 218.00 98.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 94)" fill="currentColor">
<g id="node1" class="node">
<ellipse fill="currentColor" stroke="currentColor" cx="3.24" cy="-44" rx="3.24" ry="3.24"/>
</g>
<g id="node3" class="node">
<ellipse fill="none" stroke="currentColor" cx="109.23" cy="-72" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="109.23" y="-65.83" font-family="Times,serif" font-size="14.00">a</text>
</g>
<g id="edge1" class="edge">
<path fill="none" stroke="currentColor" d="M6.84,-44.98C10.67,-46.41 18.07,-49.09 24.48,-51 42.98,-56.51 64.05,-61.79 80.43,-65.67"/>
<polygon fill="currentColor" stroke="currentColor" points="79.42,-69.03 89.95,-67.9 81.01,-62.21 79.42,-69.03"/>
</g>
<g id="node4" class="node">
<ellipse fill="none" stroke="currentColor" cx="109.23" cy="-18" rx="18" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="109.23" y="-11.82" font-family="Times,serif" font-size="14.00">b</text>
</g>
<g id="edge3" class="edge">
<path fill="none" stroke="currentColor" stroke-dasharray="5,2" d="M6.83,-42.96C10.64,-41.44 18.03,-38.62 24.48,-36.75 42.79,-31.44 63.72,-26.76 80.07,-23.4"/>
<polygon fill="currentColor" stroke="currentColor" points="80.47,-26.89 89.59,-21.49 79.09,-20.02 80.47,-26.89"/>
<text xml:space="preserve" text-anchor="middle" x="48.86" y="-37.7" font-family="Times,serif" font-size="14.00">on throw</text>
</g>
<g id="node2" class="node">
<text xml:space="preserve" text-anchor="middle" x="187.23" y="-37.83" font-family="Times,serif" font-size="14.00">result</text>
</g>
<g id="edge2" class="edge">
<path fill="none" stroke="currentColor" d="M126.35,-66.06C134.3,-63.13 144.2,-59.49 153.64,-56.01"/>
<polygon fill="currentColor" stroke="currentColor" points="154.78,-59.32 162.95,-52.58 152.36,-52.75 154.78,-59.32"/>
</g>
<g id="edge4" class="edge">
<path fill="none" stroke="currentColor" stroke-dasharray="5,2" d="M126.72,-23.64C134.54,-26.32 144.18,-29.61 153.4,-32.77"/>
<polygon fill="currentColor" stroke="currentColor" points="152.26,-36.08 162.86,-36 154.53,-29.46 152.26,-36.08"/>
</g>
</g>
</svg>
</div>

Runs the left node; if it **throws**, runs the right on the *original* input.
This tries `fetchLive`, and falls back to `fetchCached` if it throws:

```scala
val convert = 
     (fetchLive <|> fetchCached) ~> applyRates ~> total
```

Under an error-tracking effect the recovery flows through that effect's error channel instead of a thrown exception.

!!! note "Concurrency needs a concurrent effect"
    `&>` and `*>` only run their branches concurrently when compiled to a concurrent
    effect such as `Future` (or IO), via `.compile[Future]`. Plain `unsafeRun` (the
    `Id` interpreter) has no threads, so it runs them in sequence.

!!! note "Auto-flatten and `.zip`"
    Chaining fan-outs auto-flattens the tuple: `a & b & c` has type
    `Node[X, (A, B, C)]` (not `((A, B), C)`), and the same holds for `&>`:
    ```scala
    import etl4s._

    val n1 = Node[String, Int](_.length)
    val n2 = Node[String, String](_.toUpperCase)
    val n3 = Node[String, Boolean](_.nonEmpty)

    val flat = n1 & n2 & n3
    flat.unsafeRun("hi")
    ```
    `flat` has type `Node[String, (Int, String, Boolean)]`, and you will get:
    ```
    (2, "HI", true)
    ```
    If you already have a node whose output is a nested tuple, `.zip` flattens it:
    ```scala
    val nested  = (n1 & n2) & n3
    val flatten = nested.zip
    ```
    `nested` has type `Node[String, ((Int, String), Boolean)]` and `flatten` has type `Node[String, (Int, String, Boolean)]`.
