# xlang language specification v0.2 — P9 aggregate addendum

This addendum extends v0.1 with concrete aggregate and pointer syntax.

```ebnf
item           ::= aggregate_decl | v0.1_item
aggregate_decl ::= ('struct' | 'union') IDENT '{' field_decl+ '}'
field_decl     ::= IDENT ':' type ';'
type           ::= v0.1_type | IDENT
primary        ::= v0.1_primary | '[' (expr (',' expr)*)? ']'
                 | IDENT '{' (IDENT ':' expr (',' IDENT ':' expr)*)? '}'
unary          ::= 'sizeof' '(' type ')' | v0.1_unary
cast           ::= unary ('as' type)*
```

- `int`, `string`, and pointers have size/alignment 8; `bool` has size/alignment 1.
- Struct fields retain declaration order, are aligned individually, and the final
  size is rounded to the maximum field alignment.
- Union fields all have offset zero; union size is the aligned maximum field size.
- Arrays are contiguous with stride equal to the element layout size.
- Recursive aggregates must cross a pointer; recursive by-value layouts are rejected.
- `null` converts to any pointer. `*void` is the generic byte-addressed pointer.
- Aggregate arguments use the teaching ABI's by-reference convention. Aggregate
  return types must be written as pointers.
- `&`, `*`, indexing, member access, pointer arithmetic, `sizeof`, and explicit
  integer/pointer casts are statically checked.
