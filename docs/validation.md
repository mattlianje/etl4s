
**etl4s** provides a lightweight validation system that lets you accumulate errors instead of failing at the first problem. 

| Component | Description | Example |
|:----------|:------------|:--------|
| `Validated[T]` | Type class for validating objects | `Validated[User] validator` |
| `ValidationResult` | Success (Valid) or failure (Invalid) | `Valid(user)` or `Invalid(errors)` |
| `require` | Validate a condition | `require(user, user.age >= 18, "Minor")` |
| `success` | Create successful validation | `success(user)` |
| `failure` | Create failed validation | `failure("Invalid data")` |
| `&&` | Combine with AND logic | `validateName && validateEmail` |
| `||` operator | Combine with OR logic | `isPremium || isAdmin` |

### Creating validators

Define your data model:
```scala
case class User(name: String, email: String, age: Int)
```

Create a simple validator:
```scala
import etl4s._

val validateUser = Validated[User] { user =>
  require(user, user.name.nonEmpty, "Name required") &&
  require(user, user.email.contains("@"), "Valid email required") &&
  require(user, user.age >= 18, "Must be 18+")
}
```

Run validation:
```scala
val result = validateUser(User("Alice", "alice@mail.com", 25))
val invalid = validateUser(User("", "not-an-email", 16))
```

You will get:
```
Valid(User(Alice,alice@mail.com,25))
Invalid(List("Name required", "Valid email required", "Must be 18+"))
```

### Working with validation results

Access validation results with pattern matching (recommended):
```scala
val user = User("Alice", "alice@gmail.com", 25)

validateUser(user) match {
  case Valid(validUser) => 
    println(s"Yay! Welcome $validUser")
    
  case Invalid(errors) => 
    println(
      s"""Oops! User $user failed validation:
         |${errors.mkString(",")}""".stripMargin
    )
}
```

Or use the provided methods:
```scala
val result = validateUser(user)

if (result.isValid) {
  /* Get the validated value with .get */
  val validUser = result.get
} else {
  /* Access error messages with .errors */
  val errorMessages = result.errors
}
```

### Composing validators

Create specialized validators:
```scala
val validateName = Validated[User] { user => 
  require(user, user.name.nonEmpty, "Name required") 
}

val validateEmail = Validated[User] { user =>
  require(user, user.email.contains("@"), "Valid email required")
}

val validateAge = Validated[User] { user => 
  require(user, user.age >= 18, "Must be 18+")
}

/* Using success/failure directly for custom logic */
val validatePremium = Validated[User] { user =>
  if (user.email.endsWith(".gov") || user.email.endsWith(".edu")) {
    success(user)
  } else {
    failure("Premium requires .gov or .edu email")
  }
}
```

Combine with logical operators:
```scala
/* AND composition - all validations must pass */
val basicValidator = validateName && validateEmail

/* A more complete validator with all checks */
val completeValidator = validateName && validateEmail && validateAge

/* OR composition - at least one validation must pass */
val flexibleValidator = validateName || validateAge 

/* Complex combinations */
val complexValidator = (validateName && validateEmail) || validateAge
```

Create conditional validators:
```scala
val conditionalValidator = Validated[User] { user =>
  /* Start with base validation */
  val baseCheck = require(user, user.name.nonEmpty, "Name required")
  
  /* Add conditional rules */
  if (user.name == "Admin") {
    baseCheck && require(user, user.age >= 21, "Admins must be 21+")
  } else if (user.email.endsWith(".gov")) {
    baseCheck && success(user)
  } else {
    baseCheck && require(user, user.age >= 18, "Must be 18+")
  }
}
```

### Validation in ETL Pipelines

Process valid and invalid records in parallel in a real pipeline:

```scala
import etl4s._

case class Record(id: String, value: Double)

val validateRecord = Validated[Record] { record =>
  require(record, record.id.nonEmpty, "Empty ID") &&
  require(record, record.value >= 0, "Negative value")
}

val sampleRecords: List[Record] = List(
  Record("A1", 10.5),
  Record("", -5.0),  /* Invalid: empty ID */
  Record("A3", 0.0),
  Record("A4", -2.3) /* Invalid: negative value */
)

val source = Extract[Unit, List[Record]](_ => sampleRecords)

val validate = Transform[List[Record], (List[Record], List[String])] { records =>
  val results = records.map(r => validateRecord(r))
  val valid = results.collect { case Valid(r) => r }
  val errors = results.collect { case Invalid(e) => e }.flatten
  (valid, errors)
}

val processValid = Transform[(List[Record], List[String]), String] { 
  case (valid, _) => s"Processed ${valid.size} valid records" 
}

val logErrors = Transform[(List[Record], List[String]), String] { 
  case (_, errors) => s"Found ${errors.size} errors: ${errors.mkString(", ")}" 
}

val pipeline = source ~> validate ~> (processValid & logErrors)

/* Run the pipeline to get both reports */
val (successReport, errorReport) = pipeline.unsafeRun(())
```
