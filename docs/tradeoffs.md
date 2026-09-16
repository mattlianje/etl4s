# Tradeoffs

## Reified pipelines

We have seen the powerful benefits of reifying our pipelines. Long-short, our programs
become descriptions, that can have many interpretations and they become fully inspectable.

There are some costs:

- **A small interpretation overhead.** Running a reified tree is a little slower than
hand composed functions with erased types. For IO-bound ETL work, this is completely trivial, but it is non-zero.
- **A fixed set of of combinators.** The node types are a closed set. You extend
a pipeline (here, by extend, we mean "add nodes") by composing the combinators that exist,
not by inventing new node kinds. In practice `Step` plus the operators (`~>`, `&>`, etc) cover the ground,
but it is not an open free structure you bolt new instructions onto.


## Concurrency comes from the effect

Concurrency is a property of the interpreter, not of the operator. `&>`, `*>`,
and `eachPar(n)` mark *where* work may run concurrently, but nothing runs in
parallel under the default `Id` interpreter (`unsafeRun`): it is fully
sequential, with no `ExecutionContext` involved.

Admittedly, this can be a bit surprising and counterintuitive but cannot be avoided without forcing
a specific concurrency implementation on the programmer.
