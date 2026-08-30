# xlang 语言规范 — v0.1

状态：草案，在 P0–P2 期间冻结。后续阶段如需扩展，会以新的次版本号发布。

xlang 是一门 C 家族的小语言。它借鉴了 C 的内存模型（值、指针、结构体、
数组、显式大小），但没有沿用 C 的语法。下面的文法是 P1 语法分析器需要
识别的形式；语义（类型、作用域、求值）会在 P2 及以后逐步细化。

## 1. 词法结构

源文件采用 UTF-8。行终止符为 LF 或 CRLF。Token 之间的空白被忽略。

### 1.1 注释

```
// 单行注释，遇到行尾结束
/* 块注释，不允许嵌套 */
```

### 1.2 标识符

```
ident       ::= ident_start ident_cont*
ident_start ::= 'A'..'Z' | 'a'..'z' | '_'
ident_cont  ::= ident_start | '0'..'9'
```

标识符区分大小写。最大长度实现相关，但至少支持 255。

### 1.3 关键字

保留字，不能作为标识符：

```
fn let return if else while for break continue
true false null
int bool void string
struct sizeof as
```

`struct`、`sizeof`、`as` 保留给后续阶段，但词法器在 P1 就会识别，避免
误当作普通标识符。

### 1.4 字面量

```
int_lit    ::= dec_lit | hex_lit
dec_lit    ::= '0' | ('1'..'9') ('0'..'9')*
hex_lit    ::= '0x' hex_digit+
hex_digit  ::= '0'..'9' | 'a'..'f' | 'A'..'F'

bool_lit   ::= 'true' | 'false'

string_lit ::= '"' string_char* '"'
string_char ::= 除 '"' '\\' 换行 外的任意 Unicode 标量
              | '\\' ( '"' | '\\' | 'n' | 'r' | 't' | '0' )
```

整型字面量为 64 位有符号（`i64`）。词法阶段的溢出即报错。字符串字面量
使用 UTF-8，运行时不可变，并以 `\0` 结尾。

### 1.5 运算符与标点

```
+ - * / %          算术
== != < <= > >=    关系
&& || !            逻辑
= += -= *= /= %=   赋值
& |                位运算（保留，P1 不要求实现）
( ) { } [ ]        分组
, ; :              标点
-> .               返回类型 / 成员访问
```

## 2. 文法

采用 EBNF。`?` 表示可选，`*` 零或多次，`+` 一或多次。终结符用引号或大写
标识。

```
program        ::= item*

item           ::= fn_decl | let_decl ';'

fn_decl        ::= 'fn' IDENT '(' params? ')' ret_type? block
params         ::= param (',' param)*
param          ::= IDENT ':' type
ret_type       ::= '->' type

let_decl       ::= 'let' IDENT (':' type)? '=' expr

type           ::= 'int' | 'bool' | 'void' | 'string'
                 | '*' type                 // 指针，P9 落地
                 | '[' INT_LIT ']' type     // 定长数组，P9

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

优先级由文法结构决定。赋值右结合；其余二元运算符左结合。

`else if` 不是独立结构，它天然由“`else` 后可接 `if` 语句”得到。

## 3. 类型（P1 参考、P2 起为规范）

- `int` 为 64 位有符号整数，溢出取模。这是 xlang 的选择，C 语义更严格，
  P2 会明确我们采用的准确规则。
- `bool` 只有 `true` / `false`，与整型不互转。
- `string` 是不可变、以 null 结尾的 UTF-8 字符串值。P8 将它作为 xrt
  `printf` ABI 的参数类型开放。
- `void` 只能作为函数返回类型。
- 指针与数组类型在 P1 只做语法识别，语义检查从 P9 起。

## 4. 程序

一个 xlang 程序由若干顶层 `fn` 与 `let` 组成。执行从
`fn main() -> int` 开始，`main` 的返回值就是进程退出码。这一约束从 P4
起强制执行。

## 5. 留待后续阶段

- 结构体、联合体、`sizeof`、类型转换（`as`） — P9
- 模块系统、导入 — 暂未安排
- 泛型 — 暂未安排

## 6. 示例（P1 可解析）

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
