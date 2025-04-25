
Lets walk though building a pipeline with etl4s.

Before we get started just import **etl4s** and create a synthetic domain model:

```scala
import etl4s.*

case class User(id: String,
                name: String, 
                email: String, 
                domain: String = "", 
                userType: String = "")
```

## Creating etl4s blocks
Under the hood, `Extract`, `Transform` and `Load` are just type aliases for the same `Node` type,
but this helps express intent clearly in your code.

We start by creating some **etl4s** blocks to extract data:
```scala
val getUsers: Extract[Unit, List[User]] = Extract(List(
  User("u1", "Alice", "alice@example.com"),
  User("u2", "Bob", "BOB@example.com")
))
```

Next, we create some `Transform` nodes:
```scala
val normalizeEmails = Transform[List[User], List[User]] { users =>
  users.map(u => u.copy(email = u.email.toLowerCase))
}

val extractDomains = Transform[List[User], List[User]] { users =>
  users.map(u => u.copy(domain = u.email.split("@").last))
}
```

Finally, a `Load` node to "save" our results:
```scala
val saveUsers = Load[List[User], Unit] { users =>
  users.foreach { user =>
    println(s"${user.name}: ${user.email} (domain: ${user.domain})")
  }
}
```


## Stitching a pipeline
The `~>` operator connects nodes to form pipelines:
```scala
val pipeline: Pipeline[Unit, Unit] = 
     getUsers ~> normalizeEmails ~> extractDomains ~> saveUsers
```

## Running your pipeline
Run your pipeline with `unsafeRun` or `safeRun`

`safeRun` will just catch any exceptions thrown with a `scala.util.Try`

```scala
pipeline.unsafeRun(())
```

You run nodes just like calling functions:
```scala
val times5: Transform[Int, Int] = Transform(_ * 5)

times5(5) // 25
```


## Adding error handling
**etl4s** provides two methods to handle failures:

### `withRetry`
Give retry capability using the built-in `withRetry`:
```scala
import etl4s.*

var attempts = 0

val riskyTransformWithRetry = Transform[Int, String] {
    n =>
      attempts += 1
      if (attempts < 3) throw new RuntimeException(s"Attempt $attempts failed")
      else s"Success after $attempts attempts"
}.withRetry(maxAttempts = 3, initialDelayMs = 10)

val pipeline = Extract(42) ~> riskyTransformWithRetry
pipeline.unsafeRun(())
```
This prints:
```
Success after 3 attempts
```

### `onFailure`
Catch exceptions and perform some action:
```scala
import etl4s.*

val riskyExtract =
    Extract[Unit, String](_ => throw new RuntimeException("Boom!"))

val safeExtract = riskyExtract
                    .onFailure(e => s"Failed with: ${e.getMessage} ... firing missile")
val consoleLoad: Load[String, Unit] = Load(println(_))

val pipeline = safeExtract ~> consoleLoad
pipeline.unsafeRun(())
``` 
This prints:
```
Failed with: Boom! ... firing missile
```


## Making your pipeline config-driven
First, create a config object:
```scala
case class PipelineConfig(
  emailDomain: String,
  retryAttempts: Int,
  startDate: java.time.LocalDate,
  endDate: java.time.LocalDate
)
```

We add a `startDate` and `endDate` because we know our pipeline will have to support backfills.

### `Env`
The `Reader` monad, also called `Env` lets your wrap your functions in the environment they need. (Its a common
functional programming abstraction). It Comes batteries included with **etl4s**:

```scala
val configuredExtract: Env[PipelineConfig, Extract[Unit, List[User]]] =
 Env { env =>
  Extract { (_: Unit) =>
    List(
      User("u1", "Alice", s"alice@${env.emailDomain}"),
      User("u2", "Bob", s"bob@${env.emailDomain}")
    )
  }
}
```

### The `withEnv` aliases
The Etl4sEnv trait defines these aliases to streamline working with environment-aware components:

```scala
trait Etl4sEnv[T] {
  type ExtractWithEnv[A, B]   = Env[T, Extract[A, B]]
  type TransformWithEnv[A, B] = Env[T, Transform[A, B]]
  type LoadWithEnv[A, B]      = Env[T, Load[A, B]]
  type PipelineWithEnv[A, B]  = Env[T, Pipeline[A, B]]
}
```

These aliases create shorthand forms for environment-wrapped ETL components.

This lets you mix environment-aware components with regular ones.

When you write `getUsers ~> normalizeEmails ~> saveData`, etl4s handles the complexity of connecting config-dependent components (`getUsers`, `saveData`) with regular ones (`normalizeEmails`) without making you write flatMap code.

Here's how to use the `Etl4sEnv` trait for your config-aware components:

```scala
object UserService extends Etl4sEnv[PipelineConfig] {
  def getUsers: ExtractWithEnv[Unit, List[User]] = Env { env =>
    Extract { (_: Unit) =>
      List(
        User("u1", "User 1", s"user1@${env.emailDomain}"),
        User("u2", "User 2", s"user2@${env.emailDomain}")
      )
    }
  }

  val normalizeEmails = Transform[List[User], List[User]] { users =>
    users.map(u => u.copy(email = u.email.toLowerCase))
  }

  val extractDomains = Transform[List[User], List[User]] { users =>
    users.map(u => u.copy(domain = u.email.split("@").last))
  }

  def enrichUsers: TransformWithEnv[List[User], List[User]] = 
   Env { env =>
    Transform { users =>
      users.map { user =>
        val userType = if (user.domain == env.emailDomain) "Internal"
                       else "External"
        user.copy(userType = userType)
      }
    }.withRetry(maxAttempts = env.retryAttempts)
  }

  def saveUsers: LoadWithEnv[List[User], Unit] = Env { env =>
    Load { users =>
      println(s"Saving ${users.size} users with domain ${env.emailDomain}")
    }
  }
}
```

This is super powerful. We can now create a pipeline that states: "I need an instance of `PipelineConfig`
before you run me"

```scala
import UserService._

val configPipeline: Env[PipelineConfig, Pipeline[Unit, Unit]] = 
     getUsers ~> normalizeEmails ~> extractDomains ~> enrichUsers ~> saveUsers
```

To give your pipeline the config it need use the `provideEnv` method:
```scala
val myConfig = PipelineConfig(
  emailDomain = "example.com",
  retryAttempts = 3,
  startDate = java.time.LocalDate.of(2025, 1, 1),
  endDate = java.time.LocalDate.of(2025, 1, 3)
)

val configuredPipeline: Pipeline[Unit, Unit] = 
     configPipeline.provideEnv(myConfig)
```

You can now run your pipeline:
```
configuredPipeline.unsafeRun(())
```

## Adding logging (`tap`)

The `tap` method allows you to observe values flowing through your pipeline without modifying them. 
This is useful for logging, debugging, or collecting metrics.

```scala
import etl4s._

val sayHello   = Extract("hello world")
val splitWords = Transform[String, Array[String]](_.split(" "))
val toUpper    = Transform[Array[String], Array[String]](_.map(_.toUpperCase))

val pipeline = sayHello ~> 
               splitWords ~>
               tap(words => println(s"Processing ${words.length} words")) ~> 
               toUpper

val result = pipeline.unsafeRun(())
// Result: Array("HELLO", "WORLD")
// ...but also prints: "Processing 2 words"
```

## Chaining multiple pipelines
Chain pipelines with `~>` like UNIX pipes:
```scala
import etl4s.*

val p1 = Pipeline((i: Int) => i.toString)
val p2 = Pipeline((s: String) => s + "!")

val p3: Pipeline[Int, String] = p1 ~> p2
```

## Complex chaining
Connect the output of two pipelines to a third:
```scala
import etl4s.*

val namePipeline = Pipeline((_: Unit) => "John Doe")
val agePipeline = Pipeline((_: Unit) => 30)

val combined: Pipeline[Unit, Unit] =
  for {
    name <- namePipeline
    age <- agePipeline
    _ <- Extract(s"$name | $age") ~> Transform(_.toUpperCase) ~> Load(println(_))
  } yield ()
```

## Testing your pipelines
You can test your nodes and pipelines with any framework, they are just functions under the hood

### Single node
Just call individual nodes like testing functions:
```scala
test("normalizeEmails should convert emails to lowercase") {
  val normalizeEmails = Transform[List[User], List[User]] { users =>
    users.map(u => u.copy(email = u.email.toLowerCase))
  }
  
  val testUsers = List(
    User("u1", "Alice", "ALICE@EXAMPLE.COM"),
    User("u2", "Bob", "Bob@Example.com")
  )
  
  val result = normalizeEmails(testUsers)
  
  assert(result(0).email == "alice@example.com")
  assert(result(1).email == "bob@example.com")
}
```

### Complete pipeline
Here is how you would test a pipeline:
```scala
test("user processing pipeline should work end-to-end") {
  // Mock the save operation to verify results
  val mockSave = Load[List[User], List[User]] { results => 
    // Return the same data so we can assert on it
    results 
  }
  
  // Build your test pipeline
  val pipeline = getUsers ~> normalizeEmails ~> extractDomains ~> mockSave
  
  val result = pipeline.unsafeRun(())
  
  assert(result.length == 2)
  assert(result(0)._2 == "example.com")
  assert(result(1)._1.email == "bob@example.com")
}
```
