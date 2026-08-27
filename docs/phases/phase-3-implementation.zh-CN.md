# 阶段 3 源码实现分析：把 AST 降级为三地址 XIR

> 对应提交：`阶段3：实现三地址 XIR 与控制流降级`
>
> 核心源码位于 `xlangc/src/main/java/com/xlang/compiler/xir/`，重点阅读 `Xir`、`Lowerer` 与 `XirPrinter`。

## 1. 为什么不直接把 AST 翻译成机器码

AST 适合表达源码结构，却不适合直接描述机器执行。一个 `while` AST 节点包含条件和循环体，但 CPU 并没有 `while` 指令；短路 `&&` 看起来是一个二元表达式，实际必须根据左值决定是否执行右侧。

中间表示（Intermediate Representation, IR）把语言概念逐步改写成更接近机器的概念：

```text
AST：If / While / Binary / Call
            |
            | Lowering
            v
XIR：BasicBlock + 三地址指令 + Jump/Branch/Return
            |
            v
后端：具体寄存器、栈槽、机器指令
```

LLVM 的 IR 同样把函数组织为基本块，基本块由指令序列组成，并以 terminator 结束；控制流由分支连接。参见 [LLVM Language Reference](https://www.llvm.org/docs/LangRef.html)。XIR 借用的是这一核心思想，但规模更小。

## 2. XIR 的层次结构

`Xir` 用嵌套的 sealed interface 与 record 定义中间表示，主要层次是：

- `Module`：整个编译单元；
- `Global`：全局数据；
- `Function`：函数签名与基本块集合；
- `BasicBlock`：一串普通 `Instruction` 加一个 `Terminator`；
- `Value`：临时值、参数、常量或全局引用；
- `Instruction`：计算或有副作用的步骤；
- `Terminator`：跳转、条件分支或返回。

这样的结构把“数据流”和“控制流”同时显式化。值告诉我们一条指令依赖什么；块之间的边告诉我们下一步可能执行哪里。

## 3. 三地址形式为什么更容易生成机器码

源码表达式：

```xlang
a + b * c
```

降级后近似为：

```text
%t1 = mul %b, %c
%t2 = add %a, %t1
```

每条指令只做一个主要运算，最多显式列出目标和两个输入，所以常被称为 three-address code。机器后端不再递归理解一棵任意深的表达式树，只需逐条处理 `mul`、`add` 等有限指令。

这也提供一个稳定分界：以后即使 XLang 增加 `for`、复合赋值等语法，只要它们能降成现有 XIR，后端不必认识新语法。

## 4. Basic Block 为什么必须有且只有一个终结指令

基本块是“只能从开头进入，除末尾外没有控制转移”的直线代码。阶段 3 将 terminator 从普通指令列表中单独建模，使非法状态更难出现：

- 块不能没有去向；
- `return` 后不能再悄悄追加普通指令；
- 条件分支明确列出 true/false 两个目标；
- 后端无需扫描整块寻找最后一个跳转。

MLIR 也规定 block 包含一串 operations，最后通常由 terminator 指明后继，参见 [MLIR Language Reference: Blocks](https://mlir.llvm.org/docs/LangRef/)。严格的结构约束是在数据模型层消除歧义。

## 5. `Lowerer` 的核心工作方式

Lowering 不是优化，它首先是一种语义保持的翻译：输出应与输入程序表达同样的行为，只是使用更低层的词汇。

`Lowerer` 遍历已通过类型检查的 AST，并维护：

- 当前函数和当前基本块；
- 临时值编号与块编号；
- 源码 symbol 到 XIR value/storage 的映射；
- `break` 与 `continue` 的目标栈；
- 类型检查阶段产生的表达式类型信息。

它内部使用可变 builder，是因为生成控制流时需要逐步创建块和追加指令；函数完成后再冻结成不可变 record。这个边界很重要：构造过程需要方便修改，构造结果则应稳定、易验证和易传递。

## 6. 控制流究竟如何“展开”

### 6.1 `if`

一个有 `else` 的 `if` 通常生成三个目标块：

```text
entry:
  branch %cond, then, else
then:
  ...
  jump merge
else:
  ...
  jump merge
merge:
  ...
```

AST 的嵌套结构消失，取而代之的是块标签与显式边。

### 6.2 `while`

循环通常拆为 condition、body、exit：

```text
current -> condition
condition --true--> body
condition --false-> exit
body -> condition
```

`continue` 跳 condition，`break` 跳 exit。`Lowerer` 用目标栈支持嵌套循环：进入内层循环压入新目标，退出后弹出，因而总是使用最近的循环。

### 6.3 短路逻辑

`a && b` 不能无条件先算 `b`，因为 `a` 为 false 时右侧不得执行。尤其当 `b` 有函数调用等副作用时，这一点属于语言语义，而不是优化。

因此短路表达式也被降为条件分支和汇合块。它说明“表达式”并不总是纯粹的数据计算，有些表达式本身会产生控制流。

## 7. 变量、shadowing 与全局初始化

类型检查阶段已经把名字解析为作用域中的符号。Lowerer 不能再只按变量文本命名，否则内外两个 `x` 会冲突；它为不同声明分配不同的 XIR 身份。

全局变量初始化也不能凭空发生。阶段 3 把需要执行的顶层初始化整理到专用的 `$module_init` 函数。将来链接多个目标文件时，启动代码可以按顺序调用各模块初始化函数，然后再进入 `main`。

这是一项很关键的分层：数据布局属于目标文件/链接器，而“初始化要执行哪些计算”属于 IR 中的代码。

## 8. XIR 是 SSA 吗

不是严格的 Static Single Assignment（SSA）。虽然许多临时值只定义一次，但 XIR 的 `Copy` 等形式允许同一逻辑位置被再次赋值，也没有在控制流汇合处引入 phi 节点。

阶段 3 选择的是“SSA 风格的命名 + 教学型三地址码”。它避免一开始就实现支配关系、phi 插入与 SSA 销毁，同时仍能把表达式和控制流清晰地暴露给后端。

不要因为名字形如 `%t1` 就认定 IR 是 SSA；真正判断标准是每个定义是否唯一，以及控制流汇合如何选择来自不同前驱的值。

## 9. `XirPrinter` 为什么不是可有可无

IR 是编译器各阶段之间的契约。稳定文本打印可以：

- 让人直接观察 lowering 是否正确；
- 做 golden/snapshot 测试；
- 区分“前端 AST 错了”和“后端选指令错了”；
- 在没有调试器时保留可复现的中间证据。

可观察性越早建立，后续链接器和虚拟机出现错误时越容易定位。

## 10. 当前实现的边界

- 不做常量折叠、死代码删除等优化；
- 不做严格 SSA；
- 不分配物理寄存器；
- 不决定最终代码或数据地址；
- 一个编译单元内生成简单 `$module_init`，跨文件顺序留给链接阶段。

这些边界正是 IR 的价值：它既不再受源码语法束缚，又尚未绑定到具体机器布局。

## 11. 建议你亲手验证

1. 打印 `1 + 2 * 3` 的 XIR，确认每条指令只做一个运算。
2. 打印有/无 `else` 的 `if`，画出基本块边。
3. 把 `break` 放进两层循环，确认它指向最近的 exit。
4. 给 `a && sideEffect()` 降级，确认右侧只在必要路径执行。
5. 检查每个块是否恰好拥有一个 terminator。

## 12. 学完本阶段应该能回答

- AST 为什么不适合作为机器后端的唯一输入？
- 三地址形式怎样降低后端复杂度？
- 基本块与 terminator 的不变量是什么？
- 短路逻辑为什么必须变成控制流？
- XIR 为什么不能仅凭临时变量命名就称为 SSA？

下一阶段会定义一台真实可执行这些低层操作的虚拟机器，并实现 fetch-decode-execute 循环。
