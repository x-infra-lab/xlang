# xlang 语言规范 v0.2 — P9 聚合类型增量

本文是 v0.1 的增量，正式定义聚合类型与指针语法。

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

- `int`、`string`、指针的大小与对齐均为 8；`bool` 均为 1。
- struct 字段保持声明顺序，逐字段对齐，最终大小向最大字段对齐量取整。
- union 所有字段偏移均为 0，大小是最大字段大小按最大对齐量取整。
- 数组元素连续排列，步长等于元素布局大小。
- 递归聚合必须经过指针；按值递归布局会被拒绝。
- `null` 可转换为任意指针；`*void` 是按字节寻址的通用指针。
- 教学 ABI 对聚合参数采用按引用传递；聚合返回值必须显式写成指针。
- `&`、`*`、索引、成员访问、指针算术、`sizeof` 与整数/指针显式转换均接受静态检查。
