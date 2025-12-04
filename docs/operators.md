# Operators

| Operator | Name | What it does | Result type |
|----------|------|--------------|-------------|
| `~>` | Chain | `a ~> b` - output of `a` feeds into `b` | `B` |
| `&` | Fan-out | `a & b` - run both sequentially, same input | `(A, B)` |
| `&>` | Parallel | `a &> b` - run both concurrently, same input | `(A, B)` |
| `>>` | Sequence | `a >> b` - run both in order, return `b`'s result | `B` |
| `.If` / `.ElseIf` / `.Else` | Branch | conditional routing | varies |
