# xlang

[English](README.md) | [简体中文](README.zh-CN.md)

**xlang** 是一个分阶段的教学型工具链。它唯一的目标是把 C 语言工具链"打开"给你看：
编译器如何把源码变成目标文件、链接器如何回填地址、内核如何分发内存、`malloc`
如何切分堆、一个 struct 到底是如何在内存里排布的。

xlang 不生成真正的机器码，而是**在 Java 里模拟一整台机器**：虚拟 CPU、虚拟 RAM、
带页表和 syscall 的虚拟操作系统、虚拟链接器，以及一份用 xlang 自身写成的迷你 libc。
这样每一层都只是一个普通的 Java 类，可以拿调试器一步步看；每一次分配、每一次重定位、
每一次 syscall 都是可以打印出来的。

每个阶段都可独立运行、有 git tag、并交付三样东西：

- 一条能跑的 CLI 命令；
- JUnit 单元测试；
- 位于 `docs/phases/` 的中英双语阶段文档。

## 当前状态

**P3 -- 三地址 XIR。** 通过检查的程序现在会降级为显式函数和基本块，包含
三地址指令、控制流终结指令及短路分支，并可通过 `xlang ir` 获得稳定文本输出。

- [语言规范 v0.1](docs/spec/xlang-spec-v0.1.zh-CN.md)
- [阶段 0 -- 骨架](docs/phases/phase-0.zh-CN.md)
- [阶段 1 -- 词法器与语法分析器](docs/phases/phase-1.zh-CN.md)
- [阶段 2 -- 类型与词法作用域](docs/phases/phase-2.zh-CN.md)
- [阶段 3 -- 三地址 XIR](docs/phases/phase-3.zh-CN.md)
- [实现计划](docs/IMPLEMENTATION_PLAN.zh-CN.md)

## 模块划分

| 模块        | 职责                                                | 交付阶段   |
|-------------|-----------------------------------------------------|------------|
| `xlangc`    | 编译器：词法、语法、语义、XIR、后端                 | P1--P6     |
| `xlangvm`   | 虚拟机：XCPU、XMachine、XOS（页表、syscall）        | P4--P5     |
| `xld`       | 链接器：段合并、符号解析、重定位                    | P7         |
| `xrt`       | 用 xlang 写的迷你 libc：start、write、malloc、printf | P8         |
| `xlang-cli` | 统一 `xlang` 命令入口，转发到以上模块               | P0 起      |

## 阶段路线图

| 阶段  | 交付物                                                           |
|-------|------------------------------------------------------------------|
| P0    | Gradle 骨架、CLI、规范 v0.1、文档                                |
| P1    | 手写词法器 + 递归下降语法分析器                                  |
| P2    | 类型检查、符号表、作用域解析                                     |
| P3    | XIR：三地址码、基本块                                            |
| P4    | XMachine + XCPU 启动，能跑手写机器码程序                         |
| P5    | XOS：页表、`mmap`/`brk`、内存可视化 CLI                          |
| P6    | xlangc 后端：XIR -> XMachine 指令集，生成 `.xo` 目标文件         |
| P7    | xld：链接 `.xo`，应用重定位，输出 `.xex` 可执行                  |
| P8    | xrt：xlang 写的迷你 libc（syscall、`malloc`/`free`、`printf`）    |
| P9    | 结构体/联合体/指针/数组，带布局可视化                            |
| P10   | 综合 demo：一段 xlang 程序跑在自研的全套栈上                     |

## 构建与测试

```bash
./gradlew build
./gradlew :xlang-cli:run --args="help"
./gradlew :xlang-cli:run --args="phase"
./gradlew :xlang-cli:run --args="tokens program.xl"
./gradlew :xlang-cli:run --args="parse program.xl"
./gradlew :xlang-cli:run --args="check program.xl"
./gradlew :xlang-cli:run --args="ir program.xl"
```

构建使用 Gradle 的 Java 21 toolchain；如果本地找不到 JDK 21，Gradle 会自动下载。

## 非目标

- 不产出真的 ELF/Mach-O 二进制。我们选择模拟一台机器，把内存、重定位、syscall 这些
  真正有趣的东西留在可见的位置。
- 不追求速度。所有代码都以可读性优先。
- 不与 C 源码兼容。xlang 借鉴的是 C 的内存模型，不是它的语法。
