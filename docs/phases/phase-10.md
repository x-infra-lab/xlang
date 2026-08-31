# Phase 10 — end-to-end capstone

**Current phase:** P10. The planned P0–P10 teaching roadmap is complete.

P10 contributes one executable specification: [`examples/capstone.xl`](../../examples/capstone.xl).
The same source file is checked at every public compiler boundary and then run
through the object, linker, runtime, machine, and operating-system layers.

## What the program exercises

The capstone declares a padded `Report` struct and an overlapping `Reading`
union. It allocates a `Report` through xrt, writes a fixed array, passes a
pointer to a loop-based `sum` function, checks the result, prints it together
with `sizeof(Report)`, frees the allocation, and returns zero.

| Phase | Evidence in the capstone |
|-------|--------------------------|
| P1 | Tokens and AST for declarations, expressions, and statements |
| P2 | Names, scopes, calls, operators, and aggregate types are checked |
| P3 | `while`/`if` become basic blocks; memory access becomes explicit XIR |
| P4 | The linked instructions execute on XCPU/XMachine |
| P5 | Stack and heap addresses pass through the page table; XOS serves syscalls |
| P6 | The backend emits a relocatable XO01 object |
| P7 | xld resolves application/xrt symbols and emits XE01 |
| P8 | xrt supplies `start`, `malloc`, `free`, and `printf` |
| P9 | Struct, union, array, pointer, member, index, cast, and `sizeof` are used |

## Run it

```bash
./gradlew :xlang-cli:run --args="compile examples/capstone.xl -o /tmp/capstone.xo"
./gradlew :xlang-cli:run --args="link /tmp/capstone.xo --runtime --verbose -o /tmp/capstone.xex"
./gradlew :xlang-cli:run --args="syscall-trace /tmp/capstone.xex"
```

The program's captured output is:

```text
xlang capstone total=42
Report layout bytes=48
```

The syscall trace contains `brk` while allocating, `write` while printing, and
a final `exit(status=0)`. Verbose linking independently exposes the relocation
patches that connect calls and data addresses.

## Integration finding

The capstone initially exhausted the old 2 KiB virtual stack. The teaching
backend intentionally gives every XIR value a separate stack slot; the larger
application frame plus nested xrt formatting and allocator calls therefore
crossed the previous mapping. P10 expands the mapped process stack to 3 KiB.
Page protections, address translation, and underflow checks remain unchanged.

## Verification

`XrtTest.capstoneExampleExercisesTheCompleteToolchain` reads the checked-in
example instead of duplicating it. It asserts successful lexing, parsing, type
checking, XIR pointer lowering, object emission, verbose relocation, execution,
exact output, successful exit, and the expected syscall categories.
