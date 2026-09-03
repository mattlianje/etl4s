# Tradeoffs

## Reified pipelines

A single **etl4s** node wraps a plain function: `Transform(_ * 2)` holds an
`Int => Int` alongside a name and type tags. But the operators (`~>`, `&`, `*`,
`If`, `each`, ...) do not eagerly compose those functions into one. They build an
immutable tree that *describes* what should happen without doing any of it. An
interpreter folds that tree into an actual `A => B` (or `A => F[B]`) only when you
run it.

What you get for that:

- **One description, many interpretations.** The same value folds into a plain
  `A => B`, into `A => F[B]` for any `Effect[F]`, into a `Trace`, or into
  lineage. You choose the interpreter at the edge with `.compile[F]`, not when
  you build the pipeline.
- **It is inspectable.** Because the shape is data, you can read it back:
  `.stages`, `.toMermaid`, `.toDot`, and [lineage](lineage.md) all come from
  walking the tree, no execution required.

What it costs:

- **A small interpretation overhead.** Running a reified tree is a little slower
  than hand-composed functions. For IO-bound ETL work this is noise, but it is
  not zero.
- **A fixed set of combinators.** The node types are a closed set. You extend a
  pipeline by composing the combinators that exist, not by inventing new node
  kinds. In practice `Step` plus the operators cover the ground, but it is not an
  open free structure you bolt new instructions onto.
- **`flatMap` is a one-way door for introspection.** `node.flatMap(b => ...)`
  builds the next node from a runtime value, so the interpreter cannot see past
  it until it runs. Such a step shows up as `<dynamic>` in `.stages` and the
  diagrams. Reach for it when you genuinely need a data-dependent continuation;
  prefer `If`/`ElseIf` when the branching is structural, and it stays visible.

## Concurrency comes from the effect

Concurrency is a property of the interpreter, not of the operator. `&>`, `*>`,
and `eachPar(n)` mark *where* work may run concurrently, but nothing runs in
parallel under the default `Id` interpreter (`unsafeRun`): it is fully
sequential, with no `ExecutionContext` involved.

Parallelism materializes when you compile to an effect whose `Effect[F]`
implements `both` concurrently. `Future` ships that way, and you can supply your
own `given Effect[F]` to run on Cats Effect, ZIO, Kyo, and the like:

```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val parallel = e1 &> e2 &> e3

parallel.unsafeRun(())
parallel.compile[Future].unsafeRun(())
```
`parallel.unsafeRun(())` runs the branches sequentially under the default `Id`
interpreter, while `parallel.compile[Future].unsafeRun(())` runs them
concurrently under `Future`.

!!! note "Keep in mind"
    - Concurrency is opt-in twice: you write `&>` *and* you compile to a
      concurrent effect. Defining `&>` alone does nothing on its own.
    - Under `Future`, each `&>` branch submits its own `Future`. Avoid folding a
      large collection with `&>`; use `eachPar(n)` to bound how many run at once.

See [Effect polymorphism](effect-polymorphism.md) for how `.compile[F]` chooses
the interpreter, and how to bring your own.

## Tracing

`Trace` is a plain immutable value holding the result and elapsed time. No
ambient state, no allocation beyond the wrapper. That keeps the core tiny and
predictable, but it means etl4s does not do logging, metrics, or distributed
tracing for you. Bring your own tools inside node bodies when you need them.
