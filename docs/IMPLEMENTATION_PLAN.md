# xlang implementation plan

This is the working plan for how the toolchain is built up phase by
phase. It is deliberately concrete: for every phase we list which files
appear, which CLI command becomes real, and what "done" means.

Every phase ends with:

- `./gradlew build` green,
- a git tag `phase-N`,
- a doc pair `docs/phases/phase-N.md` + `docs/phases/phase-N.zh-CN.md`,
- `CURRENT_PHASE` updated,
- the CLI's `phase` subcommand reflecting the new state.

## P0 — Scaffold

- Gradle multi-module workspace, JDK 21 toolchain, JUnit 5.
- `xlang-cli` with `version` / `help` / `phase` and typed stubs for
  every future subcommand.
- Language spec v0.1 (`docs/spec/xlang-spec-v0.1.md`).

**Done.**

## P1 — Lexer + parser

Land in `xlangc`:

- `token/Token`, `token/TokenType`, `SourceSpan` value types.
- `Lexer`: hand-written, single-pass, UTF-8 safe. Handles ints (dec +
  hex), strings with escapes, identifiers, keywords, all P1 operators,
  line and block comments.
- `ast/*`: sealed AST for `Program`, `FnDecl`, `LetDecl`, `Block`, all
  statements and expressions in the v0.1 grammar.
- `Parser`: recursive descent matching the v0.1 grammar. Reports a
  `Diagnostic` with `SourceSpan` on error and recovers to the next
  statement boundary so we can report more than one error per file.
- `AstPrinter`: pretty printer used by tests and the CLI.

CLI:

- `xlang parse <file>` prints the AST tree.
- `xlang tokens <file>` dumps the token stream (useful for debugging).

**Done when:** every example in the spec parses cleanly and prints back
to a canonical AST form; JUnit covers happy paths, escape sequences,
operator precedence, and at least four error-recovery cases.

**Done.**

## P2 — Types and scoping

- `SymbolTable` with lexical scopes.
- `TypeChecker`: assigns a type to every expression, rejects
  ill-typed programs, checks `return` against the declared return type,
  enforces `main() -> int`.
- Diagnostics carry `SourceSpan` + a short human explanation.
- CLI: `xlang check <file>` runs lex + parse + typecheck.

**Done.**

## P3 — XIR

- Three-address IR with basic blocks, `Function`, `Module`.
- Lowering pass from typed AST to XIR.
- CLI: `xlang ir <file>` prints XIR.

**Done.**

## P4 — XMachine and XCPU

- `XCPU` with registers, flags, program counter.
- `XMachine` owns a `byte[]` RAM and a decoder for a small ISA.
- Hand-assembled programs run.
- CLI: `xlang run <hex-program>`, `xlang trace <hex-program>`.

**Done.**

## P5 — XOS

- Page table, `mmap`/`brk`, protection bits, page fault reporting.
- Simple `write` syscall so `run` can produce output.
- CLI: `xlang mem show`, `xlang mem map`.

**Done.**

## P6 — xlangc backend

- XIR -> XMachine ISA. Emits `.xo` objects.
- CLI: `xlang compile <file>` becomes real.

**Done.**

## P7 — xld

- Reads `.xo` files, merges sections, resolves globals, patches
  relocations, writes `.xex`. Logs every patched byte in verbose mode.
- CLI: `xlang link` becomes real.

**Done.**

## P8 — xrt

- Mini libc written in xlang: `start`, `write`, `exit`, `malloc`,
  `free`, `printf`.
- CLI: `xlang syscall-trace` becomes real.

**Done.**

## P9 — Aggregates and layout

- Struct, union, pointer, array parsing/checking/lowering with an
  explicit layout algorithm you can print.
- CLI: `xlang layout <type>` becomes real.

**Complete.**

## P10 — Capstone demo

- One xlang program that exercises P1–P9 end to end and produces
  observable output through the xrt syscalls.

**Complete.**
