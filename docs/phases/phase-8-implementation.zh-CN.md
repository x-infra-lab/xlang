# 阶段 8 源码实现分析：xrt 迷你 libc、内建桥接与 syscall 追踪

> 对应提交：`阶段8：实现 xrt 迷你 libc 与 syscall 追踪`
>
> 重点结合 `xrt/src/main/xlang/xrt.xl`、`xrt` 的 Java 资源加载入口、`BuiltinFunctions`、`XBackend`、`XOS` 与 CLI 的 `link --runtime` 阅读。

## 1. runtime 在编译器和操作系统之间做什么

应用程序不应该到处写 syscall 编号、寄存器约定和内存操作细节。xrt 提供稳定的语言级接口：

```text
应用：write / printf / malloc / free / exit
            |
            v
xrt.xl：参数整理、格式化、分配策略
            |
            v
compiler builtins：__syscall / __address / __load64 / __store64
            |
            v
XMachine SYSCALL + XOS：特权服务
```

系统调用是内核暴露的受控入口，而 libc 风格函数是更友好的用户态包装。Linux 的 syscall 概览同样区分系统调用入口和通常由 C 库提供的包装函数，参见 [`syscalls(2)`](https://man7.org/linux/man-pages/man2/syscalls.2.html)。XLang 的编号和 ABI 是自定义的，但分层思想相同。

## 2. 为什么 runtime 主体写在 `xrt.xl`，而不是 Java

`xrt/src/main/xlang/xrt.xl` 实现 `start`、`write`、`exit`、`malloc`、`free` 与 `printf`。Java 侧的 Xrt 主要负责定位和加载这份资源，并让正常编译管线把它编成 XO01。

这样做有三个目的：

1. runtime 与普通 XLang 代码使用同一 ABI，能真实检验编译器；
2. runtime 可以和应用一样被链接，不在宿主 JVM 中开后门；
3. 后续语言能力增强时，可直接用目标语言迭代标准库。

若所有库函数都写成 Java intrinsic，示例程序虽然能运行，却绕过了编译器、目标文件和链接器最需要验证的路径。

## 3. 为什么仍然需要 `BuiltinFunctions`

纯 XLang 目前无法直接发出 SYSCALL 指令，也没有一等指针类型来读写 allocator 元数据。因此后端识别少量、严格定义的 builtins：

- `__syscall`：按 XOS ABI 放置参数并发出机器 syscall；
- `__address`：取得对象/字符串的目标地址；
- `__load64`：从目标虚拟地址读取 64 位值；
- `__store64`：向目标虚拟地址写入 64 位值。

这些函数形成 compiler/runtime boundary：名字与签名在前端可见，后端则把调用替换为特殊机器序列。builtin 集合越小越好，因为每个 builtin 都是绕过普通函数调用语义的编译器特例。

阶段 8 用整数暂时承载地址。这是阶段 9 引入正式 pointer type 前的桥梁，因此 runtime 对地址算术非常谨慎；不能据此认为一般整数和指针在语言设计上永远等价。

## 4. `start` 为什么不等于 `main`

启用 runtime 时，链接器把最终 entry 设为 `start`，而不是直接进入用户 `main`。`start` 负责建立语言运行时约定、调用 `main`，并把返回值交给 `exit` syscall。

```text
VM entry -> xrt.start -> user main -> xrt.exit -> XOS halts CPU
```

这使“main 返回”与“进程结束”成为两层概念。用户函数遵循普通 calling convention；只有最外层 runtime 把返回值转换成进程退出状态。

CLI 的 `link --runtime` 会把 runtime object 加入链接输入并切换入口。runtime 是一个普通静态链接输入，而非 VM 隐式魔法。

## 5. `write` 与 `exit` 包装了什么

`write` 把友好的函数参数转交给 `__syscall`，最终由 XOS 验证 guest buffer 并输出。`exit` 提交退出状态；XOS 记录 syscall event、保存状态并停止 CPU。

阶段 8 的 syscall trace 记录编号、参数、返回值或事件。机器 trace 回答“执行了哪条指令”，syscall trace 回答“程序向 OS 请求了什么”。两者层次不同，配合才能解释程序行为。

XOS 继续负责信任边界：即使调用来自官方 xrt，传入的地址与长度也必须检查，因为应用可能直接调用 builtin，runtime 自身也可能有 bug。

## 6. `malloc` 为什么从 `brk` 开始

最小 allocator 使用 XOS 的 program break 获取连续堆空间。每个块在用户可见 payload 前放一个 16 字节 header，用来记录块大小以及 free-list 链接/状态；返回地址指向 header 之后。

申请过程概括为：

1. 将请求大小按 8 字节对齐；
2. 遍历 free list 寻找第一个足够大的块（first fit）；
3. 找到则摘下并复用；
4. 找不到则推进 `brk`，在新空间写 header；
5. 返回 payload 地址。

对齐保证 64 位 load/store 的地址布局一致，也为将来存放不同类型留出基础。

## 7. `free` 为什么通常不把内存立刻还给 OS

`free` 根据 payload 地址退回 header，把块插入 free list；后续 `malloc` 可复用。它不一定降低 program break。

GNU C Library 手册也说明，释放的空间通常保留在程序内部的 free list 中供后续分配复用，而不是每次都归还操作系统，参见 [glibc: Freeing Memory Allocated with malloc](https://sourceware.org/glibc/manual/2.44/html_node/Freeing-after-Malloc.html)。阶段 8 只实现这个基本思想，不等同于 glibc 的工业 allocator。

当前 allocator 不做块拆分和相邻空闲块合并。因此它可能产生内部/外部碎片：一个很大的 free block 被小请求整体占用，两个相邻小块也不能合成大块。first fit 的价值是算法短、行为容易追踪，而不是最优内存利用率。

### 为什么 header 藏在 payload 前面

`free(ptr)` 只收到用户指针，没有额外 size 参数。固定大小 header 让它通过 `ptr - HEADER_SIZE` 找回分配元数据。这是许多 allocator 的基本布局思想，但损坏或伪造指针会破坏元数据；阶段 8 尚未实现 hardened allocator 检查。

## 8. `printf` 为何如此克制

阶段 8 的 `printf` 只支持教学所需的格式子集，例如一个 `%d` 与 `%%`，并通过 `write` 输出。整数转十进制需要处理符号、逐位取模/除法以及倒序输出。

它可能逐字节调用 write，这在真实系统上效率很差，但能最大程度复用当前字符串/内存能力，并让 syscall trace 清楚展示每一步。后续可通过缓冲区批量输出优化，而不改变对应用公开的 `printf` 接口。

格式化函数是很好的端到端测试：它同时覆盖循环、条件、算术、内存访问、函数调用和 syscall。

## 9. 为什么 runtime 编译允许没有 `main`

普通应用必须提供合法 `main`，但库本身只是提供 `start` 等定义，单独编译时不应被应用入口规则拒绝。因此编译入口区分 application 与 library 模式：library 允许 unresolved external `main` 留给链接阶段解析。

这再次体现编译与链接的边界：编译 runtime 时只知道它引用 `main`；最终应用链接时才必须找到唯一用户定义。若到最终链接仍找不到，xld 报 undefined symbol。

## 10. 为什么栈空间在阶段 8 被扩大

阶段 6 的朴素后端给每个 XIR value 分配栈槽，runtime 的 `printf` 和 allocator 比早期示例拥有更多临时值、调用和局部状态。单页栈容易耗尽，因此启动映像扩大为多页栈。

这是实现约束导致的资源调整，不是语言语义。更成熟的寄存器分配和栈槽复用能缩小 frame；在那之前，给足栈空间是保持正确性的合理措施。

## 11. 当前 runtime 的边界

- 地址暂时用整数表达，缺少静态指针类型保护；
- malloc 是 first-fit free list，不 split、不 coalesce；
- 无 double-free、越界或元数据破坏防护；
- printf 格式子集很小且无缓冲；
- syscall 数量有限；
- xrt 静态链接进每个程序，没有动态 libc。

这些限制应被理解为后续阶段的接口，而不是可用于生产环境的 libc 承诺。

## 12. 一次端到端调用链

程序执行 `printf("value=%d\n", 42)`：

1. Parser/TypeChecker 将其识别为对 runtime 声明的普通调用；
2. 后端按 XMachine ABI 压入参数并 CALL `printf`；
3. xrt.xl 扫描格式串，将 42 转成十进制字符；
4. xrt 的 `write` 调用 `__syscall` builtin；
5. 后端生成 SYSCALL 指令序列；
6. XOS 验证字符串虚拟地址并输出；
7. syscall trace 留下参数和结果；
8. `printf` 返回写入数量，最终 `main` 返回，`start` 调用 exit。

这条链贯穿了前端、IR、后端、目标文件、链接器、runtime、VM 和 OS，正是阶段 8 的真正里程碑。

## 13. 建议你亲手验证

1. 用 `link --runtime` 与不带 runtime 分别链接，比较 entry 和 symbols。
2. 连续 malloc/free/malloc，确认 free-list block 被复用。
3. 请求不同大小并观察 8 字节对齐和 16 字节 header。
4. 打印负数、`%%` 与普通字符，沿 xrt.xl 追踪分支。
5. 对照 instruction trace 和 syscall trace，区分 CPU 行为与 OS 服务。

## 14. 学完本阶段应该能回答

- libc 风格 wrapper 与 syscall 的职责为何不同？
- runtime 为何优先用 XLang 实现，只保留少量 compiler builtins？
- `start`、`main` 与 `exit` 各在哪一层？
- free list 为什么能复用内存，又会怎样产生碎片？
- 为什么最终链接时才要求 runtime 的 `main` 引用被解析？

到阶段 8，XLang 已拥有一条可闭环运行的教学工具链。继续学习时，建议始终沿“表示是什么、谁拥有最终决定权、边界处保存了哪些未决信息”这三个问题阅读源码。
