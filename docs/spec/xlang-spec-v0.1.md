# xlang language specification — v0.1

Status: draft, frozen for P0–P2. Every later phase may extend this spec
under a new minor version.

xlang is a tiny C-family language. It borrows C's memory model (values,
pointers, structs, arrays, explicit sizes) but not C's grammar. The
grammar below is what the P1 parser is expected to accept. Semantics
(types, scoping, evaluation) are refined in P2 and beyond.

## 1. Lexical structure

Source files are UTF-8. Line terminators are LF or CRLF. Whitespace
between tokens is ignored.

### 1.1 Comments

```
// line comment, terminates at end of line
/* block comment, does not nest */
```

### 1.2 Identifiers

```
ident      ::= ident_start ident_cont*
ident_start ::= 'A'..'Z' | 'a'..'z' | '_'
ident_cont  ::= ident_start | '0'..'9'
```

Identifiers are case-sensitive. Maximum length is not fixed but must be
at least 255.

### 1.3 Keywords

Reserved and cannot be used as identifiers:

```
fn let return if else while for break continue
true false null
int bool void string
struct sizeof as
```

`struct`, `sizeof`, and `as` are reserved for later phases but the lexer
already recognises them so P1 doesn't misclassify them as identifiers.

### 1.4 Literals

```
int_lit    ::= dec_lit | hex_lit
dec_lit    ::= '0' | ('1'..'9') ('0'..'9')*
hex_lit    ::= '0x' hex_digit+
hex_digit  ::= '0'..'9' | 'a'..'f' | 'A'..'F'

bool_lit   ::= 'true' | 'false'

string_lit ::= '"' string_char* '"'
string_char ::= any Unicode scalar except '"' '\\' NL
              | '\\' ( '"' | '\\' | 'n' | 'r' | 't' | '0' )
```

Integer literals are 64-bit signed (`i64`). Overflow at lex time is an
error. String literals are UTF-8, immutable, and null-terminated at
runtime.

### 1.5 Operators and punctuation

```
+ - * / %       arithmetic
== != < <= > >= comparison
&& || !         logical
= += -= *= /= %= assignment
& |             bitwise (reserved for later, not required in P1)
( ) { } [ ]     grouping
, ; :           punctuation
-> .            member/return-type
```

## 2. Grammar

Presented in EBNF. `?` means optional, `*` zero-or-more, `+`
one-or-more. Terminals are quoted or in UPPERCASE.

```
program        ::= item*

item           ::= fn_decl | let_decl ';'

fn_decl        ::= 'fn' IDENT '(' params? ')' ret_type? block
params         ::= param (',' param)*
param          ::= IDENT ':' type
ret_type       ::= '->' type

let_decl       ::= 'let' IDENT (':' type)? '=' expr

type           ::= 'int' | 'bool' | 'void' | 'string'
                 | '*' type                 // pointer, materialised in P9
                 | '[' INT_LIT ']' type     // fixed-size array, P9

block          ::= '{' stmt* '}'

stmt           ::= let_decl ';'
                 | 'return' expr? ';'
                 | 'if' '(' expr ')' block ('else' (if_stmt | block))?
                 | 'while' '(' expr ')' block
                 | 'break' ';'
                 | 'continue' ';'
                 | block
                 | expr ';'

expr           ::= assign
assign         ::= logic_or (('=' | '+=' | '-=' | '*=' | '/=' | '%=') assign)?
logic_or       ::= logic_and ('||' logic_and)*
logic_and      ::= equality ('&&' equality)*
equality       ::= relational (('==' | '!=') relational)*
relational     ::= additive (('<' | '<=' | '>' | '>=') additive)*
additive       ::= multiplicative (('+' | '-') multiplicative)*
multiplicative ::= unary (('*' | '/' | '%') unary)*
unary          ::= ('-' | '!' | '*' | '&') unary | postfix
postfix        ::= primary ( '(' args? ')' | '[' expr ']' | '.' IDENT )*
args           ::= expr (',' expr)*
primary        ::= INT_LIT | STRING_LIT | 'true' | 'false' | 'null'
                 | IDENT
                 | '(' expr ')'
```

Operator precedence follows the grammar structure above. Assignment is
right-associative; every other binary operator is left-associative.

`else if` is not a distinct construct; it falls out of allowing an `if`
statement after `else` without braces.

## 3. Types (informative for P1, normative from P2)

- `int` is 64-bit signed. Overflow wraps. This is a xlang choice; C is
  more careful and P2 will document the exact semantics we adopt.
- `bool` is `true` or `false`. Not an integer.
- `string` is an immutable, null-terminated UTF-8 string value. P8 exposes it
  as a parameter type for the xrt `printf` ABI.
- `void` is only valid as a function return type.
- Pointer and array types parse in P1 but are not semantically checked
  until P9.

## 4. Programs

A xlang program is a set of top-level `fn` and `let` items. Execution
starts at `fn main() -> int`. The value returned by `main` becomes the
process exit code. This is enforced from P4 onward.

## 5. Reserved for later phases

- structs, unions, `sizeof`, casts (`as`) — P9
- module system, imports — not scheduled
- generics — not scheduled

## 6. Example (P1-parseable)

```
fn add(a: int, b: int) -> int {
    return a + b;
}

fn main() -> int {
    let x: int = 40;
    let y: int = 2;
    return add(x, y);
}
```
