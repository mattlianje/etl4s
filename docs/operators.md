# Operators

| Operator | Name | What it does | Result type |
|----------|------|--------------|-------------|
| `~>` / `.andThen` | Chain | `a ~> b` - output of `a` feeds into `b` (`.andThen` is an alias) | `Node[A, C]` |
| `&` / `&>` | Fan-out | `a & b` - run both with the **same** input (`&>` runs them concurrently) | `Node[A, (B, C)]` |
| `*` / `*>` | Product | `a * b` - run on **different** inputs (`*>` runs them concurrently) | `Node[(A, C), (B, D)]` |
| `>>` | Sequence | `a >> b` - run in order, keep `b`'s result | `Node[A, C]` |
| <code>&#124;</code> | Fan-in | <code>a &#124; b</code> - route an `Either` input to the matching branch | `Node[Either[A, C], B]` |
| `+` | Choice | `a + b` - route an `Either` input through independent branches | `Node[Either[A, C], Either[B, D]]` |
| <code>&lt;&#124;&gt;</code> | Fallback | <code>a &lt;&#124;&gt; b</code> - if `a` throws, run `b` on the same input | `Node[A, B]` |
| `.If` / `.ElseIf` / `.Else` | Branch | conditional routing | varies |

## `~>` chain (and `.andThen`)

Feeds the output of one node into the next. `.andThen` is an alias:

```scala
import etl4s._

val extract   = Node[String, Int](_.length)
val transform = Node[Int, String](n => s"length: $n")

val pipeline = extract ~> transform
pipeline.unsafeRun("hello")

extract.andThen(transform).unsafeRun("hi")
```

You will get:
```
"length: 5"
"length: 2"
```

## `&` fan-out (shared input)

Runs both nodes with the **same** input and pairs the results into a tuple:

```scala
import etl4s._

val getLength = Node[String, Int](_.length)
val getUpper  = Node[String, String](_.toUpperCase)

val both = getLength & getUpper
both.unsafeRun("hi")
```

You will get:
```
(2, "HI")
```

## `&>` concurrent fan-out

Same shape as `&`, but the two branches are eligible to run concurrently (see the note below):

```scala
import etl4s._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val fetchA = Node[Int, Int](_ + 1)
val fetchB = Node[Int, Int](_ * 10)

val both = fetchA &> fetchB
both.compile[Future].unsafeRun(5)
```

You will get:
```
Future((6, 50))
```
The branches run concurrently.

## `*` product (different inputs)

Unlike `&`/`&>` (which broadcast one shared input), `*` feeds `_._1` to the left node and `_._2` to the right node, so the two nodes have **different** input types:

```scala
import etl4s._

val parseName = Node[String, String](_.trim)
val parseAge  = Node[Int, Int](_ + 1)

val both = parseName * parseAge
both.unsafeRun(("  alice  ", 29))
```
`both` has type `Node[(String, Int), (String, Int)]`.

You will get:
```
("alice", 30)
```

## `*>` concurrent product

Same shape as `*`, but the two independent branches are eligible to run concurrently under a concurrent effect:

```scala
import etl4s._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val left  = Node[String, Int](_.length)
val right = Node[Int, Int](_ * 2)

val both = left *> right
both.compile[Future].unsafeRun(("hello", 21))
```

You will get:
```
Future((5, 42))
```

## `>>` sequence (keep last)

Runs both nodes in order with the **same** input (the first for its side effects), and keeps the second node's result:

```scala
import etl4s._

val log  = Node[Int, Unit](n => println(s"got $n"))
val save = Node[Int, String](n => s"saved:$n")

val store = log >> save
store.unsafeRun(7)
```

Prints:
```
got 7
```
and returns `saved:7`.

## `|` fan-in (route an `Either` in)

Where `&` broadcasts one input to two nodes, `|` does the reverse: it takes an
`Either` input and routes `Left` to the left node and `Right` to the right node,
merging both to a **common output** type:

```scala
import etl4s._

val fromInt = Node[Int, String](i => s"int:$i")
val fromStr = Node[String, String](s => s"str:$s")

val merged = fromInt | fromStr
merged.unsafeRun(Left(1))
merged.unsafeRun(Right("hi"))
```
`merged` has type `Node[Either[Int, String], String]`.

You will get:
```
"int:1"
"str:hi"
```

## `+` choice (the dual of `*`)

`+` is to `|` what `*` is to `&`: it routes an `Either` input through **independent**
branches, but unlike `|` it keeps the `Either` on the way out
(`Either[A, C] => Either[B, D]`):

```scala
import etl4s._

val dbl = Node[Int, Int](_ * 2)
val up  = Node[String, String](_.toUpperCase)

val ch = dbl + up
ch.unsafeRun(Left(21))
ch.unsafeRun(Right("hi"))
```
`ch` has type `Node[Either[Int, String], Either[Int, String]]`.

You will get:
```
Left(42)
Right("HI")
```

## `<|>` fallback (try, then recover)

Runs the left node; if it **throws**, runs the right node on the *original* input.
Unlike [`onFailure`](failures.md) (which takes a `Throwable => B`), the alternative
is a full node that re-runs on the same input:

```scala
import etl4s._

val primary  = Node[String, Int](_.toInt)
val fallback = Node[String, Int](_ => 0)

val safe = primary <|> fallback
safe.unsafeRun("7")
safe.unsafeRun("oops")
```

You will get:
```
7
0
```

Under a concurrent/error-tracking effect the recovery happens via that effect's
error channel rather than a thrown exception:

```scala
import etl4s._
import scala.util.Try

safe.compile[Try].unsafeRun("oops")   // Success(0)
```

!!! note "Concurrency needs a concurrent effect"
    `&>` and `*>` only run their branches concurrently when compiled to a concurrent
    effect such as `Future` (or IO), via `.compile[Future]`. The plain synchronous
    `unsafeRun` (the `Id` interpreter) has no threads, so it runs the branches
    sequentially. The result is identical, only the execution differs. No
    `ExecutionContext` is needed for plain `unsafeRun`.

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
