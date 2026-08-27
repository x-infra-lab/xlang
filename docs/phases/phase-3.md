# Phase 3 — Three-address XIR

**Current phase:** P3.

P3 turns the typed tree from P2 into a small, explicit intermediate language.
The AST says what the source looks like; XIR says which primitive operations
and control-flow edges later machine phases must execute.

## XIR structure

- A `Module` owns global declarations and functions.
- Each `Function` declares typed virtual parameters and an ordered list of
  `BasicBlock`s.
- Every block contains three-address instructions followed by exactly one
  terminator: `jump`, `branch`, `return`, or `unreachable`.
- Values use stable prefixes: globals are `@name`, locals are uniquely named
  `%name.N`, and compiler temporaries are `%tN`.

Instructions include typed constants, copies, unary operations, binary
operations, and calls. Top-level initializer expressions are retained in a
synthetic `$module_init` function rather than being hidden or discarded.

## Lowering control flow

- `if` / `else` become then, else, and join blocks.
- `while` becomes condition, body, and exit blocks.
- `break` jumps to the current loop exit; `continue` jumps to its condition.
- `&&` and `||` produce branches, so their right operand remains genuinely
  short-circuited rather than becoming an eager binary operation.
- Lexically shadowed variables receive distinct virtual names.

P3 only runs after the full P2 pipeline succeeds. Invalid source produces the
original source-span diagnostics and no partial XIR module.

## Try it

```bash
./gradlew :xlang-cli:run --args="ir examples/hello.xl"
```

The output is deterministic and intentionally verbose enough to step through
in a debugger or use as golden test data.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

Tests cover arithmetic decomposition, unique locals, calls with and without
results, globals and module initialization, `if`, loops, break/continue,
short-circuit control flow, fully terminated blocks, invalid-input gating, and
CLI output.

## Next

P4 introduces XMachine and XCPU and runs the first hand-assembled instruction
stream. P6 will later translate XIR into that machine ISA.
