# 阶段 2 — 类型与词法作用域

**当前阶段：** P2。

P2 为 P1 生成的语法树补充语义：解析声明过的名字，为每个访问到的表达式
赋予语义类型，并拒绝违反 v0.1 类型规则的程序。

## P2 交付内容

- 嵌套 `SymbolTable`：拒绝同一作用域重复声明，同时允许子块进行正常的词法遮蔽。
- 分离的变量符号与函数符号。检查函数体之前先收集全部函数签名，因此支持前向调用。
- `int`、`bool`、`void` 和推断得到的字符串字面量类型；另有错误类型用于抑制
  无意义的级联诊断。
- 检查声明与类型推断、一元/二元运算、赋值、函数调用与参数、布尔条件、
  `return`，以及只能在循环内使用的 `break` / `continue`。
- 对非 void 函数执行保守的返回路径检查。
- 按 P2 实现计划校验入口签名 `fn main() -> int`。
- 对仍留到 P9 的指针、数组、索引、成员和 null 语义给出明确诊断。
- `xlang check <file>`：依次执行词法分析、语法分析、名字解析和类型检查。

## 示例

```xlang
fn add(a: int, b: int) -> int {
    return a + b;
}

fn main() -> int {
    let answer = add(40, 2);
    let correct: bool = answer == 42;
    if (correct) { return answer; } else { return 1; }
}
```

```bash
./gradlew :xlang-cli:run --args="check examples/hello.xl"
```

检查成功时输出 `type check passed`。诊断沿用 P1 的 `文件:行:列` 格式，并以
非零状态退出。

## 验证

```bash
./gradlew build
./gradlew phaseInfo
```

P2 测试覆盖类型推断、所有运算符类别、嵌套作用域与重复声明、前向调用、参数、
返回值、入口签名、条件、循环控制、未定义名字、推迟到 P9 的能力以及 CLI 成败路径。

## 下一阶段

P3 会把已定型 AST 降级成显式的三地址中间表示，并引入函数和基本块。
