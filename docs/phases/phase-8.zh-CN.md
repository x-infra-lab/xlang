# 阶段 8 — xrt 迷你 libc 与 syscall trace

**当前阶段：** P8。

P8 加入第一版用户态运行时。其控制逻辑并未藏进 Java 快捷实现：
`xrt/src/main/xlang/xrt.xl` 会像普通程序一样经过解析、类型检查、XIR 降级、
XO01 编译，最后作为独立目标文件与应用一起链接。

## 运行时 ABI

xrt 导出以下源码级函数：

| 函数 | 行为 |
|------|------|
| `start() -> int` | 调用应用 `main`，然后执行 `exit` |
| `write(fd, buffer, length) -> int` | syscall 1 的薄包装 |
| `exit(status) -> void` | 通过 syscall 2 终止进程 |
| `malloc(size) -> int` | 由 `brk` 支撑、按 8 字节对齐的 first-fit 分配 |
| `free(pointer) -> void` | 把内存块放回空闲链表 |
| `printf(format, value) -> int` | 支持一个整数 `%d` 与 `%%` |

在 P9 引入受类型检查的指针前，指针暂用整数地址表示。每个分配块有 16 字节头部，
保存大小和下一个空闲块地址。分配器会复用完整的 first-fit 块，暂不切分或合并，
从而让每次堆修改都容易在调试器中观察。

## 编译器边界

xrt 的绝大部分都是普通 xlang。只有四个保留 intrinsic 构成最窄的机器边界：
`__syscall`、`__address`、`__load64`、`__store64`。类型检查器了解其签名，后端将其
直接降级到 P4/P5 ISA。应用也能看到公开 xrt ABI 的声明，因此会生成普通未解析调用，
由 xld 在选择 `--runtime` 时完成解析。

P8 还允许把 `string` 写在参数类型中，并增加库编译模式，使 xrt 的 `start` 可以引用
应用提供的外部 `main`。

## 内核 syscall 与追踪

XOS 现在实现并记录：

| 编号 | 调用 | `r0` 之后的寄存器 |
|------|------|-------------------|
| 1 | `write` | `r1=fd`、`r2=buffer`、`r3=length` |
| 2 | `exit` | `r1=status` |
| 3 | `brk` | `r1=目标地址`（`0` 表示查询） |

`exit` 会停止 CPU 并保留进程状态；`brk` 失败返回 `-1`。每次已分发 syscall 都会形成
不可变事件，保存参数、结果及转义后的 write 内容。为容纳 P8 的调用深度，可见栈映射
也从 4 页扩到 8 页。

## CLI

```bash
./gradlew :xlang-cli:run --args="compile examples/runtime-demo.xl -o /tmp/demo.xo"
./gradlew :xlang-cli:run --args="link /tmp/demo.xo --runtime -o /tmp/demo.xex"
./gradlew :xlang-cli:run --args="syscall-trace /tmp/demo.xex"
```

`link --runtime` 会把内置 xrt 对象放在输入对象之前；若未显式指定 `--entry`，入口自动
改为 `start`。`syscall-trace` 按 text/data 分离权限加载 XE01、运行程序、打印每次
syscall，最后报告退出状态和执行指令数。

## 验证

```bash
./gradlew build
./gradlew phaseInfo
```

测试会从源码资源编译 xrt，检查所有导出符号，打印带符号整数和百分号，扩展堆、复用
已释放内存块，并以可观测状态退出。VM 测试覆盖 `brk`/`exit` 分发与事件日志；CLI
测试完整执行 compile → runtime link → syscall trace。

## 下一阶段

P9 将以受类型检查的指针、数组、struct、union 替代整数地址约定，并提供显式布局
可视化工具。
