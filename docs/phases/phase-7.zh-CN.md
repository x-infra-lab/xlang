# 阶段 7 — 链接器与 `.xex` 可执行文件

**当前阶段：** P7。

P7 用真正的静态链接器替换了 xld 骨架。一个或多个 XO01 目标文件现在可以完成布局、
符号解析、重定位，并写成确定性的 XE01 可执行文件。实现仍刻意保持精简，可以直接在
调试器里逐步观察。

## 链接流程

xld 执行四个清晰可见的 pass：

1. 在合成的进程启动序列之后依次拼接各目标文件的 text 段；
2. 以 8 字节对象边界拼接 data 段，并把合并后的 data 放到下一虚拟内存页；
3. 构建每个对象自己的符号表和一张全局符号表。不同对象可重用局部名称，重复全局
   符号会被拒绝；
4. 按小端字节序应用全部 `ABS32` 与 `ABS64` 重定位。

启动序列先按输入顺序调用每个对象的局部 `$module_init`，再执行
`call <entry>; halt`。默认入口是全局 `main`，也可用 `--entry` 选择其它全局 text
符号。未定义符号、重复全局符号、越界符号或重定位、地址溢出及 data 中的入口都会在
写出可执行文件前报错。

## XE01 布局

`.xex` 以 ASCII 魔数 `XE01` 开头，随后保存入口、data 虚拟地址、text/data 大小、
导出符号数量、段内容和最终全局符号表。`XExecutableIO` 严格读写该格式，会拒绝截断、
重复符号、尾随字节和非法段布局。

内存镜像把 text 与 data 放在不同页面。`XExecutable` 可直接装入 XMachine：text 权限
为 `r-x`，data 为 `rw-`，原有栈仍为 `rw-`。因此全局变量可以写入，而代码页不会
被意外开放写权限。

## CLI

```bash
./gradlew :xlang-cli:run --args="compile examples/hello.xl -o /tmp/hello.xo"
./gradlew :xlang-cli:run --args="link /tmp/hello.xo -o /tmp/hello.xex"
./gradlew :xlang-cli:run --args="link /tmp/hello.xo -o /tmp/hello.xex --verbose"
```

完整形式为：

```text
xlang link <files.xo...> [-o <output.xex>] [--entry <symbol>] [-v|--verbose]
```

不传 `-o` 时输出 `a.xex`。verbose 模式会先打印重定位摘要，再为每个被回填的字节打印
一行 `旧值 -> 新值`，其中也包括合成的入口重定位。

## 验证

```bash
./gradlew build
./gradlew phaseInfo
```

测试覆盖跨对象调用并真实执行、通过 `ABS64` 访问分页隔离的可写 data、局部名称隔离、
逐字节 verbose 日志、XE01 往返，以及重复、未定义、损坏和越界输入。CLI 测试会完整
编译、链接、读取、装载并运行一个返回 42 的程序。

## 下一阶段

P8 将提供 xrt：用 xlang 编写启动/运行时例程、syscall 包装、堆分配和格式化输出。
