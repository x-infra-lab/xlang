# Phase 2 — Types and lexical scopes

**Current phase:** P2.

P2 adds meaning to the syntax tree produced by P1. It resolves declared names,
assigns a semantic type to every visited expression, and rejects programs that
violate the v0.1 type rules.

## What P2 delivers

- A nested `SymbolTable` with same-scope duplicate detection and ordinary
  lexical shadowing in child blocks.
- Separate variable and function symbols. Function signatures are collected
  before bodies are checked, so forward calls work.
- Types for `int`, `bool`, `void`, and inferred string literals, plus an error
  type that prevents one mistake from producing noisy follow-on diagnostics.
- Validation for declarations and inference, unary and binary operators,
  assignments, function calls and argument lists, boolean conditions,
  `return`, and loop-only `break` / `continue`.
- Conservative return-path checking for non-void functions.
- Entry-point validation for `fn main() -> int`, as required by the P2
  implementation plan.
- Clear diagnostics for pointer, array, indexing, member, and null semantics
  that remain intentionally deferred to P9.
- `xlang check <file>`, which runs lexing, parsing, name resolution, and type
  checking as one pipeline.

## Example

```xlang
fn add(a: int, b: int) -> int {
    return a + b;
}

fn main() -> int {
    let answer = add(40, 2);
    let correct: bool = answer == 42;
    if (correct) { return answer; } else { return 1; }
}
```

```bash
./gradlew :xlang-cli:run --args="check examples/hello.xl"
```

Successful checks print `type check passed`. Diagnostics use the same
`file:line:column` format introduced in P1 and produce a non-zero exit code.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

The P2 tests cover inference, every operator family, nested scopes and
duplicates, forward calls, arguments, returns, entry-point shape, conditions,
loop control, undefined names, deferred P9 features, and CLI success/failure.

## Next

P3 lowers the typed AST into an explicit three-address intermediate
representation with functions and basic blocks.
