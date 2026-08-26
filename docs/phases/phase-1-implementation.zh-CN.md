# 阶段 1 源码实现分析：从文本到 Token，再到 AST

> 对应提交：`阶段1：实现词法器与递归下降语法分析器`
>
> 核心源码位于 `xlangc/src/main/java/com/xlang/compiler/` 下的 `lex`、`parse`、`ast` 与 `diagnostic` 包。

## 1. 先确认你的理解

“Lexer 把文本解析成一个个 token，然后把 token 喂给 Parser 解析成 AST”——这个理解是正确的。更完整的版本是：

```text
字符 + 文件位置
    |
    v
Lexer ----词法诊断----> Diagnostic
    |
    v
Token 序列（类别、原文、值、范围）
    |
    v
Parser ---语法诊断----> Diagnostic
    |
    v
AST（保留程序结构与源码范围）
```

Token 不只是被空格切开的字符串，AST 也不只是嵌套列表。错误位置、运算符优先级、结合方向和错误恢复，决定这条前端管线是否真的可用。

## 2. 为什么必须分 Lexer 和 Parser

词法规则处理局部字符模式，例如 `123`、`name`、`==`；语法规则处理 token 之间的嵌套关系，例如 `if (...) { ... }`。拆开后，Parser 无须反复处理空白、注释和转义，Lexer 也无须理解表达式优先级。

这种分层还能给错误更准确的归属：未闭合字符串是词法错误，缺少右括号是语法错误。Robert Nystrom 的扫描器实现也采用“源码字符 → token 列表”，并让 token 携带字面量值与位置信息，参见 [Crafting Interpreters: Scanning](https://www.craftinginterpreters.com/scanning.html)。

## 3. Lexer 的状态与不变量

`Lexer` 保存源码、文件名、当前偏移、行号和列号。每次扫描 token 时，它先记录起点，再推进游标，最后用起点和终点构造 `SourceSpan`。

关键不变量是：

- `current` 总指向下一个尚未消费的字符；
- 行、列与 `current` 同步前进；
- 一个扫描分支要么产生一个 token，要么产生诊断并继续；
- 最终一定追加 EOF token。

### 3.1 最长匹配为什么重要

读到 `=` 时，Lexer 要再看一眼下一个字符，才能区分 `=` 与 `==`。这称为 maximal munch（尽可能匹配最长合法 token）。否则 `a == b` 会被错误地切成两个赋值符号。

### 3.2 标识符如何变成关键字

字母开头的字符序列先按标识符整体扫描，再查关键字表。这样 `whileCount` 不会因为以 `while` 开头就被拆坏。关键字是完整词素的分类，而不是前缀匹配。

### 3.3 字面量为什么同时保存 lexeme 和 value

`Token` 保存：

- `type`：Parser 用来决策的类别；
- `lexeme`：源码原文，便于诊断和格式化；
- `value`：解码后的数字或字符串值；
- `span`：来源范围。

例如源码中的字符串含有转义写法，lexeme 适合显示给用户，value 适合后续语义与代码生成。二者不能互相完全替代。

### 3.4 为什么范围采用半开区间

`SourceSpan` 用起点包含、终点不包含的 `[start, end)`。相邻 token 可以自然满足 `previous.end == next.start`，长度也是 `end - start`，不会反复出现 `+1/-1` 的边界运算。

## 4. Parser 如何把文法翻译成代码

这是手写递归下降解析器。最直观的规则通常对应一个方法：解析声明的方法调用解析语句的方法，语句又调用表达式方法。

表达式不是用一个巨大方法完成，而是按优先级分层：

```text
assignment
  -> logical-or
    -> logical-and
      -> equality
        -> comparison
          -> term
            -> factor
              -> unary
                -> call
                  -> primary
```

低优先级函数调用高优先级函数，因此 `1 + 2 * 3` 的 AST 会把乘法放在加法的右子树。手写递归下降通过这种规则层次编码优先级，参见 [Crafting Interpreters: Parsing Expressions](https://craftinginterpreters.com/parsing-expressions.html)。

### 4.1 左结合与右结合来自哪里

`+`、`-` 等左结合运算通常用循环：先解析左侧，再不断把后续运算包到新的左树中。因此 `a-b-c` 变成 `(a-b)-c`。

赋值则递归调用自身解析右侧，所以 `a=b=c` 变成 `a=(b=c)`。结合性不是 AST 之后再修正，而是在 Parser 控制流中被确定。

### 4.2 为什么赋值左侧要单独检查

文法上，普通表达式可出现在 `=` 左边，但语义上只有可赋值位置合法。Parser 在构造赋值节点前检查左侧形状，可以直接在 `1 = x` 的 `=` 附近报告“非法赋值目标”，避免生成一个下游无法解释的 AST。

## 5. AST 为什么使用 sealed interface + record

AST 节点主要表达结构化数据：二元表达式拥有左操作数、运算符、右操作数与范围。Java record 自动提供不可变字段访问、相等性和字符串表示；sealed interface 则列出一组封闭节点类型。

这让后续类型检查器面对的是有限且明确的情况。若新增 AST 节点，模式匹配处更容易被编译器提醒。语言设计背景可参见 [JEP 395: Records](https://openjdk.org/jeps/395) 与 [JEP 409: Sealed Classes](https://openjdk.org/jeps/409)。

AST 有意丢掉空白和大多数标点，只保留程序含义所需的结构；`AstPrinter` 则提供稳定、便于测试的结构化展示。

## 6. 错误恢复为什么和成功解析同样重要

如果遇到第一个错误就停止，用户每修一个字符才能看到下一个错误。Parser 捕获当前声明或语句的解析失败后，调用同步逻辑：跳过 token，直到分号或一个明显的新语句/声明起点。

恢复点不能太激进：完全不推进会死循环，跳到文件末尾又会丢失后续诊断。阶段 1 选择语句边界，是手写 Parser 常用的折中。

`Diagnostic` 与 `SourceSpan` 贯穿 Lexer、Parser 和 AST，因此错误恢复后产生的多个错误仍能指向原始源码，而不是仅报告 token 下标。

## 7. 一次端到端示例

源码：

```xlang
let result = 1 + 2 * 3;
```

Lexer 产生 `LET IDENTIFIER EQUAL NUMBER PLUS NUMBER STAR NUMBER SEMICOLON EOF`。Parser 先识别变量声明，再解析初始化表达式。乘法层先组合 `2 * 3`，加法层随后组合 `1 + (...)`，最终形成类似：

```text
LetDecl(
  name=result,
  initializer=Binary(1, +, Binary(2, *, 3))
)
```

这就是优先级在 AST 形状中的体现。

## 8. 当前阶段刻意没有做什么

- 不判断变量是否声明；
- 不判断 `1 + "x"` 是否类型正确；
- 不生成机器码；
- 不执行程序；
- 不保留用于源码格式化的全部 trivia。

这些不是遗漏，而是分层。Parser 只回答“程序的结构是否符合文法”，阶段 2 才回答“这个结构在语义上是否成立”。

## 9. 建议你亲手验证

1. 给 Lexer 输入 `a==b` 与 `a=b`，对比 token。
2. 打印 `1+2*3` 与 `(1+2)*3` 的 AST。
3. 输入一个未闭合字符串，观察位置范围。
4. 在两个合法语句之间放一个错误语句，确认恢复后还能解析第三个语句。
5. 修改赋值解析的递归方向，观察 `a=b=c` 的树为何改变。

## 10. 学完本阶段应该能回答

- Token 为什么需要类别、原文、值和范围四部分？
- 运算符优先级为何由 Parser 调用层次决定？
- 为什么 `=` 通常右结合？
- AST 与源码文本分别保留、丢弃了什么？
- 错误恢复为何不能简单跳到 EOF？

下一阶段会沿 AST 遍历，建立作用域和类型规则，开始区分“语法正确”与“含义正确”。
