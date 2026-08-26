# Phase 0 — Scaffold

**Status:** shipped.
**Tag:** `phase-0`.

P0 exists only to set up the workshop. No compiler logic, no VM logic,
no linker logic — but every future phase has a physical place to land in.

## What P0 delivers

1. Gradle multi-module workspace with a Java 21 toolchain and JUnit 5:
   `xlang-cli`, `xlangc`, `xlangvm`, `xld`, `xrt`.
2. A single `xlang` command that dispatches to future subcommands. In
   P0 only three commands do real work: `version`, `help`, `phase`.
3. Stubs for every planned subcommand (`compile`, `link`, `run`,
   `trace`, `mem`, `layout`, `syscall-trace`). Each stub fails loudly
   and prints which phase will implement it. That way the CLI surface
   is stable from day one — later phases only flip stubs to real logic.
4. Smoke tests in every module (`scaffoldLoads`) plus CLI tests that
   pin the help output and phase reporting.
5. Language spec v0.1 — the grammar and lexical rules that P1 will
   consume. Freezing this now stops P1 design from drifting.
6. `CURRENT_PHASE` file that records the highest phase this repository
   has reached. `./gradlew phaseInfo` prints it.

## How to verify P0

```bash
./gradlew build                            # every test green
./gradlew :xlang-cli:run --args="version"  # prints "xlang 0.1.0-P0"
./gradlew :xlang-cli:run --args="help"     # lists all planned commands
./gradlew :xlang-cli:run --args="phase"    # prints "P0 (scaffold)"
./gradlew :xlang-cli:run --args="compile x.xl"
# -> exits non-zero and says: "planned for P6"
```

## Design notes carried into P1

- The CLI never talks to `System.exit` in `run(String[])`. Tests call
  `Main.run(args)` and inspect the returned exit code. This convention
  survives all later phases.
- Every subproject stays self-contained: root `build.gradle.kts` sets
  Java 21, `--release 21`, `-Werror`, and UTF-8, but does not add
  cross-module dependencies. `xlang-cli` is the only module that
  depends on the others.
- Package layout follows the modules:
  `com.xlang.cli`, `com.xlang.compiler`, `com.xlang.vm`,
  `com.xlang.linker`, `com.xlang.runtime`.
- `Main.plannedPhaseFor` is the single source of truth for which stub
  belongs to which phase. When a phase lands, replace the stub, do not
  add a second table.

## Non-goals in P0

- No lexer, no parser, no bytecode, no VM. Those are P1 onwards.
- No cross-module APIs beyond `greeting()` markers. We deliberately
  avoid inventing interfaces before we know what they need to carry.
