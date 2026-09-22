# Philosophy

## Discipline Upon Assignment

**etl4s** espouses the idea that it is beneficial to banish the assignment (`=`) operator
at the key "wiring" stage of dataflow programs.

Wiring via raw composition (`g andThen f` style) has limitations: chiefly, config and DI type-slots clutter call-sites
and run orthogonal to dataflow. And wiring via monadic stacks
doesn't impose a total discipline over the assignment operator and creating new bindings.

Say we have
```scala
for {
  e1 <- extract1
  t1 <- transform(e1)
  l <- load(t1)
} yield ()
```

this is good, our eyes can in one top-down motion read: `e > t > l`

but begins the discipline over assignment and vertical dataflow is broken if we introduce bindings:
```scala
for {
  filterDate = ???
  endDate = ???
  e1 <- extract1
  startDate = ???
  t1 <- transform(e1, filterDate)
  l <- load(t1, startDate, endDate)
} yield()
```

Our eyes now need to do:
`filterDate > endDate > e > startDate > t > (Back to filterDate) > l > (Back to startDate) > (Back to endDate)`

## Linearizability (Controlled fan-out with reconvergence)
**etl4s** deliberately channels you into a linearized "Function1 model" of "give me ONE input, I'll give you ONE output".

That said, it also lets you snap together pipelines with multiple input sources tupled together, easily fork-off conditional
branches with heterogeneous types, and chain together side-outputs.

The idea is to give the programmer clear little two ended pipes basically,
not multi-sided puzzle pieces.

Imagine we have:
```scala
import etl4s._

val p = (e1 & e2) ~> t ~> log >> saveS3.If(_ > 0)(enrich ~> dbLoad)
                                       .Else(process ~> purgatoryLoad)
```

Different branches can have different types and requirements, but once stitched together you have a single node
that has intersected upstream, and unioned downstream the branch types.


## Never nest 
**etl4s** channels the programmer to a model where they avoid stacks of nested function calls,
and everything can be reasoned about and refactored clearly at the top level.

To a degree - this is a matter of taste ... but when the entire structure of your program is a top-level composition of etl4s
nodes you get some unprecedented advantages:

1. You can inspect the _entire_ structure of your program before execution
2. Thanks to etl4s' macros ... your entire program is aware of the JVM classpaths of each block that makes it up - giving you the
ability to make your programs "blast radius aware".


## Pipelines as free arrows

In the original versions of etl4s (pre 2.x) `Node` was modelled as a monad.
That was flexible, but a monadic pipeline only exists once you run it, so we couldn't read it back beforehand.

A `Node[-A, +B]` is not a function - it is a small, sealed description of one. The operators
(`~>`, `&`, `*` ...) don't run anything; each just adds a case to an AST.

- **Arrow**: The wiring behaviour of etl4s Nodes (wrapped in Readers). `~>` chains nodes, `&` / `*` fan them out, point-free, without naming the
value flowing between. The graph is fixed before anything runs.
- **Profunctor**: The `-A, +B` variance lets you pre-map the input or post-map
the output.
- **Free**: The tree is just data, separate from what runs it. You build it first and interpret it
later: `.compile[F]` walks the same tree into `Id`, `Try`, `Future`, or your own `Effect[F]`

There has been a rich (and excitingly recent) tradition of modelling lazy programs NOT as monads.
Some of the literature that was of chief inspiration:

- Arrows: [Hughes, _Generalising Monads to Arrows_](https://www.cse.chalmers.se/~rjmh/Papers/arrows.pdf){target="_blank"}
- Arrow vs applicative vs monad: [Lindley, Wadler, Yallop, _Idioms are oblivious, arrows are meticulous, monads are promiscuous_](https://homepages.inf.ed.ac.uk/wadler/papers/arrows-and-idioms/arrows-and-idioms.pdf){target="_blank"}
- Free constructions (why a tree, not an instance): [Capriotti, Kaposi, _Free Applicative Functors_](https://arxiv.org/abs/1403.0749){target="_blank"} and [Rivas, Jaskelioff, _Notions of Computation as Monoids_](https://arxiv.org/abs/1406.4823){target="_blank"}


## What etl4s is NOT

**Not a workflow orchestrator**  
etl4s doesn't schedule jobs, or handle distributed coordination. Use Airflow or whatever flavour of scheduler for this. However,
you will find that etl4s composes cleanly with those tools. It structures the code each scheduled task runs.

**Not a data processing engine**  
etl4s doesn't move data or execute transformations. Use Spark, Flink, Pandas for that. etl4s makes your Spark/Flink job logic composable and type-safe.

**Not a replacement for monadic IO with fiber runtimes**  
If you're already using Cats Effect or ZIO, you probably do not need etl4s (though etl4s is effect
polymorphic: `.compile[F]` lets you run it on top of CE, ZIO, Kyo, or any effect with a `given Effect[F]`). It's for teams that want structure without committing to an effect system and learning its abstractions.
