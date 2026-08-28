# Phase 6 — Backend and `.xo` objects

**Current phase:** P6.

P6 connects the compiler built in P1–P3 to the machine built in P4–P5. A valid
xlang source file now travels through lexing, parsing, type checking, XIR
lowering, machine-code selection, and relocatable-object emission.

## Backend strategy

The first backend deliberately favors observability over optimization:

- Every XIR local and temporary receives an eight-byte stack slot.
- `r6` is the frame pointer, `r7` the stack pointer, and `r0` the return value.
- `r3`–`r5` are short-lived backend scratch registers.
- Constants, copies, arithmetic, comparisons, branches, calls, globals, and
  string addresses lower to the P4/P5 ISA.
- Boolean comparisons become explicit `cmp` plus conditional-jump diamonds.
- XIR basic-block edges become relocatable absolute jump targets.

This produces more instructions than a register allocator would, but every XIR
value has one obvious memory location and is easy to inspect in a trace.

## Calling convention

The caller pushes arguments in reverse order and issues `call`. The callee:

1. pushes the previous `r6`;
2. copies `r7` to `r6`;
3. reserves its stack-slot frame;
4. copies parameters from `r6 + 16 + 8*n` into local slots.

On return, the result is loaded into `r0`, `r7` is restored from `r6`, the old
frame pointer is popped, and `ret` consumes the return address. The caller then
discards its argument area. This supports any parameter count without reserving
a special four-register fast path.

## XO01 object format

`.xo` files start with the big-endian ASCII magic `XO01`, followed by section
lengths/counts and these payloads:

- `text`: encoded XMachine instructions with zero placeholders;
- `data`: eight-byte global storage and null-terminated UTF-8 strings;
- symbols: name, section, offset, size, and global/local visibility;
- relocations: target section/offset, `ABS32` or `ABS64`, symbol, and addend.

Calls and jumps use `ABS32`; addresses loaded for globals and strings use
`ABS64`. Even intra-function block edges remain relocations, so P7 can freely
concatenate text sections without rewriting the compiler output format.
`XObjectIO` performs deterministic binary read/write and rejects malformed or
truncated headers.

## Compile

```bash
./gradlew :xlang-cli:run --args="compile examples/hello.xl"
./gradlew :xlang-cli:run --args="compile examples/hello.xl -o /tmp/hello.xo"
```

Without `-o`, the object is written next to the source with a `.xo` extension.
The command prints text/data sizes and symbol/relocation counts. Invalid source
produces the original diagnostics and no partial object.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

Backend tests apply the emitted relocations with a minimal test linker and run
the result on XMachine. The exercised program uses a loop, comparisons,
branches, a five-argument call, stack frames, and returns 19. Further tests
cover globals, strings, every relocation family, exact XO01 round trips,
invalid-input gating, output-path selection, and readable CLI artifacts.

## Next

P7 implements the real `xld`: merge multiple `.xo` files, resolve global and
local symbols, apply relocations, synthesize process startup, and emit `.xex`.
