# xlang

[English](README.md) | [简体中文](README.zh-CN.md)

**xlang** is a staged teaching toolchain. Its only purpose is to make the
inside of a C toolchain visible: how a compiler turns source into an
object file, how a linker patches addresses, how the kernel hands out
memory, how `malloc` slices up a heap, how a struct is really laid out.

Instead of generating real machine code, xlang **simulates a whole machine
inside Java**: a virtual CPU, a virtual RAM, a virtual OS with page tables
and syscalls, a virtual linker, and a mini libc written in xlang itself.
That way every layer is a plain Java class you can step through in a
debugger and every `alloc`, every relocation, every syscall is loggable.

Every phase is independently runnable, is tagged in git, and ships:

- a working CLI command,
- JUnit tests,
- an English and a Simplified Chinese document under `docs/phases/`.

## Current status

**P0 -- Scaffold.** Gradle multi-module workspace, `xlang` CLI entry point
with `version`, `help`, and `phase` commands, planned command stubs that
point at their future phase, and green JUnit tests across all modules.

- [Language specification v0.1](docs/spec/xlang-spec-v0.1.md)
- [Phase 0 -- Scaffold](docs/phases/phase-0.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)

## Modules

| Module      | Role                                                          | Lands in |
|-------------|---------------------------------------------------------------|----------|
| `xlangc`    | Compiler: lexer, parser, sema, XIR, backend                   | P1--P6   |
| `xlangvm`   | Virtual machine: XCPU, XMachine, XOS (page tables, syscalls)  | P4--P5   |
| `xld`       | Linker: object merging, symbol resolution, relocations        | P7       |
| `xrt`       | Mini libc written in xlang: start, write, malloc, printf      | P8       |
| `xlang-cli` | Single `xlang` command that dispatches to everything above    | P0+      |

## Phase roadmap

| Phase | Deliverable                                                    |
|-------|----------------------------------------------------------------|
| P0    | Gradle scaffold, CLI, spec v0.1, docs                          |
| P1    | Hand-written lexer + recursive-descent parser                  |
| P2    | Type checker, symbol tables, scoped resolution                 |
| P3    | XIR: three-address form, basic blocks                          |
| P4    | XMachine + XCPU boot, first hand-written program runs          |
| P5    | XOS: page table, `mmap`/`brk`, memory visualisation CLI        |
| P6    | xlangc backend: XIR -> XMachine ISA, emit `.xo` objects        |
| P7    | xld: link `.xo` files, apply relocations, output `.xex`        |
| P8    | xrt: mini libc in xlang (syscalls, `malloc`/`free`, `printf`)  |
| P9    | Struct/union/pointer/array with layout visualiser              |
| P10   | Capstone demo written in xlang, running on the full stack      |

## Build and test

```bash
./gradlew build
./gradlew :xlang-cli:run --args="help"
./gradlew :xlang-cli:run --args="phase"
```

The build uses a Gradle Java 21 toolchain. If Gradle cannot find a JDK 21
locally it will download one on first run.

## Non-goals

- Producing real ELF/Mach-O binaries. We simulate a machine instead so
  that the interesting parts (memory, relocations, syscalls) stay visible.
- Being a fast language. Everything is optimised for legibility.
- Being source-compatible with C. xlang borrows C's memory model, not C's
  grammar.
