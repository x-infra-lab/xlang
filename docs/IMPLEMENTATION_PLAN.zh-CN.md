# xlang 实现计划

这是工具链按阶段推进的作战地图。刻意保持具体：每个阶段列出会新增哪些
文件、会让哪一条 CLI 命令变成真的、以及“完成”的判据。

每个阶段的结束态：

- `./gradlew build` 全绿；
- 打上 git tag `phase-N`；
- 交付一对文档 `docs/phases/phase-N.md` 与 `docs/phases/phase-N.zh-CN.md`；
- 更新 `CURRENT_PHASE`；
- CLI 的 `phase` 子命令能反映新状态。

## P0 — 骨架

- Gradle 多模块工作区、JDK 21 toolchain、JUnit 5。
- `xlang-cli` 提供 `version` / `help` / `phase`，其它子命令有类型化占位。
- 语言规范 v0.1（`docs/spec/xlang-spec-v0.1.md`）。

**已完成。**

## P1 — 词法器 + 语法分析器

在 `xlangc` 落地：

- `token/Token`、`token/TokenType`、`SourceSpan` 值类型。
- `Lexer`：手写、单次扫描、UTF-8 安全。支持十进制/十六进制整型、带
  转义的字符串、标识符、关键字、v0.1 所有运算符、单行与块注释。
- `ast/*`：`Program`、`FnDecl`、`LetDecl`、`Block` 以及 v0.1 文法中所
  有语句和表达式的密封 AST。
- `Parser`：递归下降实现 v0.1 文法；出错时报告带 `SourceSpan` 的
  `Diagnostic`，并恢复到下一个语句边界，能一次报告多条错误。
- `AstPrinter`：供测试与 CLI 使用的 AST 美化打印器。

CLI：

- `xlang parse <file>` 打印 AST 树。
- `xlang tokens <file>` 输出 token 流（便于排查）。

**验收：** 规范里的所有示例都能被解析并回打成规范化 AST；JUnit 覆盖
happy path、转义序列、运算符优先级，以及至少四个错误恢复用例。

**已完成。**

## P2 — 类型与作用域

- 带词法作用域的 `SymbolTable`。
- `TypeChecker`：给每个表达式赋类型，拒绝非法程序，校验 `return` 与
  声明返回类型一致，强制 `main() -> int`。
- 诊断信息带 `SourceSpan` 与简短的人类可读解释。
- CLI：`xlang check <file>` 跑一遍 lex + parse + typecheck。

**已完成。**

## P3 — XIR

- 三地址中间表示，基本块、`Function`、`Module`。
- 从已定型 AST 到 XIR 的降级 pass。
- CLI：`xlang ir <file>` 打印 XIR。

## P4 — XMachine 与 XCPU

- `XCPU`：寄存器、标志位、程序计数器。
- `XMachine` 持有 `byte[]` RAM 和小 ISA 解码器。
- 能跑手写机器码程序。
- CLI：`xlang run <hex-program>`、`xlang trace <hex-program>`。

## P5 — XOS

- 页表、`mmap`/`brk`、保护位、缺页报告。
- 简单的 `write` syscall，让 `run` 能真正产生输出。
- CLI：`xlang mem show`、`xlang mem map`。

## P6 — xlangc 后端

- XIR -> XMachine ISA，输出 `.xo` 目标文件。
- CLI：`xlang compile <file>` 变为真实实现。

## P7 — xld

- 读入 `.xo`，合并段，解析全局符号，回填重定位，输出 `.xex`；verbose
  模式下逐字节打印重定位过程。
- CLI：`xlang link` 变为真实实现。

## P8 — xrt

- 用 xlang 写的迷你 libc：`start`、`write`、`exit`、`malloc`、`free`、
  `printf`。
- CLI：`xlang syscall-trace` 变为真实实现。

## P9 — 聚合类型与布局

- struct / union / 指针 / 数组的语法、类型检查、降级；提供显式的布局
  算法，可打印。
- CLI：`xlang layout <type>` 变为真实实现。

## P10 — 综合 demo

- 一段 xlang 程序，端到端把 P1–P9 串起来，通过 xrt 的 syscall 产生
  可观察输出。
