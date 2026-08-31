# 阶段 10 — 端到端综合示例

**当前阶段：** P10。P0–P10 教学路线图全部完成。

P10 交付一份可执行规范：[`examples/capstone.xl`](../../examples/capstone.xl)。同一份源码
会在自动化测试中经过编译器的每个公共边界，随后进入目标文件、链接器、runtime、虚拟机
和虚拟操作系统。

## 这段程序覆盖了什么

综合程序声明带 padding 的 `Report` struct 和字段重叠的 `Reading` union；通过 xrt
分配 `Report`，填写定长数组，把元素指针交给带循环的 `sum`，校验结果，打印结果与
`sizeof(Report)`，最后释放内存并返回 0。

| 阶段 | 综合程序中的证据 |
|------|------------------|
| P1 | 为声明、表达式和语句生成 token 与 AST |
| P2 | 检查名字、作用域、调用、运算符与聚合类型 |
| P3 | `while`/`if` 变成基本块，内存访问变成显式 XIR |
| P4 | 链接后的指令由 XCPU/XMachine 执行 |
| P5 | 栈和堆地址经过页表，syscall 由 XOS 服务 |
| P6 | 后端生成可重定位 XO01 目标文件 |
| P7 | xld 解析应用与 xrt 符号并生成 XE01 |
| P8 | xrt 提供 `start`、`malloc`、`free`、`printf` |
| P9 | 使用 struct、union、数组、指针、成员、索引、转换和 `sizeof` |

## 运行

```bash
./gradlew :xlang-cli:run --args="compile examples/capstone.xl -o /tmp/capstone.xo"
./gradlew :xlang-cli:run --args="link /tmp/capstone.xo --runtime --verbose -o /tmp/capstone.xex"
./gradlew :xlang-cli:run --args="syscall-trace /tmp/capstone.xex"
```

程序捕获到的输出为：

```text
xlang capstone total=42
Report layout bytes=48
```

syscall trace 中能看到分配时的 `brk`、打印时的 `write`，以及最后的
`exit(status=0)`；verbose 链接日志则独立展示连接调用与数据地址的重定位回填。

## 集成测试发现的问题

综合程序最初耗尽了旧的 2 KiB 虚拟栈。教学后端刻意为每个 XIR value 分配独立栈槽，
较大的应用 frame 再叠加 xrt 格式化与分配器的嵌套调用，超过了原映射。P10 将进程栈
映射扩至 3 KiB；页权限、地址翻译与栈下溢检查保持不变。

## 验证

`XrtTest.capstoneExampleExercisesTheCompleteToolchain` 直接读取版本库中的示例，而不是
在测试里复制一份。它验证词法、语法、类型、XIR 指针降级、目标文件、verbose 重定位、
机器执行、精确输出、正常退出和 syscall 类别。
