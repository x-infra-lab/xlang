# Phase 1 — Lexer and parser

**Current phase:** P1.

P1 turns source text into a visible syntax tree. It deliberately stops before
name resolution and type checking, which belong to P2.

## What P1 delivers

- A single-pass lexer covering every v0.1 keyword, literal, operator,
  punctuation token, line comment, and block comment.
- Checked signed 64-bit decimal and hexadecimal integers and decoded string
  escapes. Lexical errors carry source spans and scanning continues where safe.
- A sealed AST for declarations, statements, expressions, and future pointer
  and fixed-array type syntax already present in the v0.1 grammar.
- A recursive-descent parser whose function structure mirrors the precedence
  grammar. Assignment is right-associative; other binary operators are left-
  associative.
- Statement-boundary recovery, allowing several diagnostics from one input.
- A deterministic indentation-based AST printer.
- `xlang tokens <file>` and `xlang parse <file>`.

## Try it

Given `hello.xl`:

```xlang
fn main() -> int {
    let answer: int = 40 + 2;
    return answer;
}
```

Run:

```bash
./gradlew :xlang-cli:run --args="tokens hello.xl"
./gradlew :xlang-cli:run --args="parse hello.xl"
```

The first command exposes exact token text and source spans. The second prints
the tree and exits non-zero if lexing or parsing produced diagnostics.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

Tests cover the specification example, comments, Unicode and every supported
escape, checked integer literals, expression precedence, postfix expressions,
future type forms, and recovery from at least four syntax errors in one file.

## Next

P2 adds lexical scopes, symbol tables, and type checking without changing this
front-end syntax boundary.
