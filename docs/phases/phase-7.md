# Phase 7 — Linker and `.xex` executables

**Current phase:** P7.

P7 replaces the xld scaffold with a real static linker. One or more XO01
objects can now be laid out, resolved, relocated, and written as a deterministic
XE01 executable. The implementation remains intentionally small enough to step
through in a debugger.

## Link pipeline

xld performs four visible passes:

1. Concatenate object text sections after a synthesized process startup sequence.
2. Concatenate data sections at eight-byte object boundaries and place the
   merged data on the next virtual-memory page.
3. Build per-object symbol maps and one global symbol map. Local names may
   repeat in different objects; duplicate globals are rejected.
4. Apply every `ABS32` and `ABS64` relocation in little-endian byte order.

The startup sequence calls every object's local `$module_init` in input order,
then executes `call <entry>; halt`. The default entry is global `main`, and
`--entry` can select another global text symbol. Undefined symbols,
duplicate globals, invalid symbol ranges, invalid patch ranges, overflowing
addresses, and entry points in data all fail before an executable is written.

## XE01 layout

An `.xex` file begins with the ASCII magic `XE01`, followed by the entry point,
data virtual address, text/data sizes, exported-symbol count, section bytes,
and the final global symbol table. `XExecutableIO` reads and writes this format
strictly and rejects truncation, duplicate symbols, trailing bytes, and invalid
segment layouts.

The in-memory image keeps text and data on different pages. `XExecutable` can
load itself into XMachine: text receives `r-x`, data receives `rw-`, and the
existing stack remains `rw-`. This makes global stores work without making code
writable.

## CLI

```bash
./gradlew :xlang-cli:run --args="compile examples/hello.xl -o /tmp/hello.xo"
./gradlew :xlang-cli:run --args="link /tmp/hello.xo -o /tmp/hello.xex"
./gradlew :xlang-cli:run --args="link /tmp/hello.xo -o /tmp/hello.xex --verbose"
```

The complete form is:

```text
xlang link <files.xo...> [-o <output.xex>] [--entry <symbol>] [-v|--verbose]
```

Without `-o`, xld writes `a.xex`. Verbose mode prints a relocation summary and
one `old -> new` line for every patched byte, including the synthesized entry
relocation.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

Tests link calls across objects and execute the result, exercise page-separated
writable data through `ABS64`, verify local-name isolation, inspect byte-level
verbose traces, round-trip XE01, and cover duplicate, undefined, malformed, and
out-of-range inputs. CLI tests compile, link, read, load, and run a program that
returns 42.

## Next

P8 supplies xrt: startup/runtime routines, syscall wrappers, heap allocation,
and formatted output written in xlang itself.
