
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

### Core Usage
Create a simple validator
```scala
import etl4s._

case class User(name: String, email: String, age: Int)

val validateUser = Validated[User] { user =>
  require(user, user.name.nonEmpty, "Name required") &&
  require(user, user.email.contains("@"), "Invalid email") &&
  require(user, user.age >= 18, "Must be 18+")
}

validateUser(User("Alice", "a@mail.com", 25))  // ✅ Valid
validateUser(User("", "bad", 15))              // ❌ Invalid(List(...))
```

### Build Modular Validators
Compose validators with `&&` and `||`
```scala
val nameCheck  = Validated[User](u => require(u, u.name.nonEmpty, "Name required"))
val emailCheck = Validated[User](u => require(u, u.email.contains("@"), "Email bad"))
val ageCheck   = Validated[User](u => require(u, u.age >= 18, "Must be 18+"))

val basic   = nameCheck && emailCheck
val fallback = nameCheck || ageCheck
```

### Conditional Validation
Write validation flows with conditional branching
```scala
val specialValidator = Validated[User] { user =>
  val base = require(user, user.name.nonEmpty, "Name required")

  if (user.name == "Admin")
    base && require(user, user.age >= 21, "Admins must be 21+")
  else if (user.email.endsWith(".gov"))
    base && success(user)
  else
    base && require(user, user.age >= 18, "Must be 18+")
}
```

### Real pipeline use
```scala
val extract = Extract[Unit, List[User]](_ => List(
  User("Alice", "alice@mail.com", 25),
  User("", "bad@", 15)
))

val split = Transform[List[User], (List[User], List[User])] { users =>
  users.partition(validateUser(_).isValid)
}

val summarize = Transform[(List[User], List[User]), String] {
  case (ok, bad) => s"${ok.size} valid / ${bad.size} invalid"
}

val pipeline = extract ~> split ~> summarize
pipeline.unsafeRun(())  // "1 valid / 1 invalid"
```
