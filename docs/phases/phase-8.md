# Phase 8 — xrt mini libc and syscall tracing

**Current phase:** P8.

P8 adds the first user-space runtime. Its control flow is not implemented as
Java library shortcuts: `xrt/src/main/xlang/xrt.xl` is parsed, checked, lowered,
compiled to XO01, and linked beside the application like any other object.

## Runtime ABI

xrt exports these source-level functions:

| Function | Behavior |
|----------|----------|
| `start() -> int` | calls application `main`, then `exit` |
| `write(fd, buffer, length) -> int` | thin wrapper over syscall 1 |
| `exit(status) -> void` | terminates the process through syscall 2 |
| `malloc(size) -> int` | aligned first-fit allocation backed by `brk` |
| `free(pointer) -> void` | returns a block to the free list |
| `printf(format, value) -> int` | supports one integer `%d` and `%%` |

Pointers remain integer addresses until P9 introduces checked pointer types.
Allocated blocks have a 16-byte header containing size and next-free address.
The allocator reuses whole first-fit blocks; it intentionally does not split or
coalesce them yet, keeping every heap mutation visible in a debugger.

## Compiler boundary

Most of xrt is ordinary xlang. Four reserved intrinsics form the narrow machine
boundary: `__syscall`, `__address`, `__load64`, and `__store64`. The type checker
knows their signatures and the backend lowers them directly to the P4/P5 ISA.
Applications also see declarations for the public xrt ABI, so they compile to
normal unresolved calls that xld resolves when `--runtime` is selected.

P8 additionally makes `string` available as a declared parameter type and adds
library compilation, which permits xrt's `start` to reference the application's
external `main`.

## Kernel syscalls and tracing

XOS now implements and records:

| Number | Call | Registers after `r0` |
|--------|------|----------------------|
| 1 | `write` | `r1=fd`, `r2=buffer`, `r3=length` |
| 2 | `exit` | `r1=status` |
| 3 | `brk` | `r1=requested address` (`0` queries) |

`exit` halts the CPU and retains the process status. `brk` failures return `-1`.
Every dispatched syscall becomes an immutable event with arguments, result, and
escaped write payload. The P8 call depth also expands the visible stack mapping
from four to eight pages.

## CLI

```bash
./gradlew :xlang-cli:run --args="compile examples/runtime-demo.xl -o /tmp/demo.xo"
./gradlew :xlang-cli:run --args="link /tmp/demo.xo --runtime -o /tmp/demo.xex"
./gradlew :xlang-cli:run --args="syscall-trace /tmp/demo.xex"
```

`link --runtime` prepends the bundled xrt object and selects `start` unless an
explicit `--entry` is supplied. `syscall-trace` loads XE01 with separated text
and data permissions, runs it, prints each syscall, and reports the final exit
status and instruction count.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

Tests compile xrt from its source resource, verify all exported symbols, print
signed integers and percent signs, grow the heap, reuse a freed block, and exit
with an observable status. VM tests cover `brk`/`exit` dispatch and event logs;
the CLI test performs compile → runtime link → syscall trace end to end.

## Next

P9 replaces integer-address conventions with checked pointers, arrays, structs,
and unions, plus an explicit layout visualizer.
