# 阶段 6 源码实现分析：从 XIR 到 XO01 目标文件

> 对应提交：`阶段6：实现 XMachine 后端与 XO01 目标文件`
>
> 核心源码位于 `xlangc/src/main/java/com/xlang/compiler/` 下的 `backend` 与 `object` 包，重点阅读 `XBackend`、`XObject`、`XObjectIO` 与 `CompileResult`。

## 1. 后端真正要补上的缺口

阶段 3 的 XIR 已经只有显式运算和控制流，但仍然使用无限数量的抽象 value、名字形式的函数/全局引用和 basic block。XMachine 只认识有限寄存器、具体栈地址、相对或绝对跳转编码。

阶段 6 的翻译链是：

```text
typed XIR
  |
  | 指令选择、调用约定、栈布局
  v
机器码字节 + data 字节
  |
  | 尚未知的地址写成 relocation
  v
XO01 XObject（sections + symbols + relocations）
```

这里生成的是目标文件而非最终可执行文件，因为单独编译一个模块时，还不知道其他模块的大小、符号地址和最终布局。

## 2. 为什么先定义 calling convention

`CALL`/`RET` 只规定如何保存返回地址。函数间若要互相理解，还必须统一：

- 参数放在哪里；
- 返回值放在哪里；
- stack pointer 与 frame pointer 用哪个寄存器；
- 哪些临时寄存器可随意覆盖；
- 栈帧怎样建立和销毁。

XMachine ABI 约定中，`r7` 是 SP，`r6` 是 FP，`r0` 放返回值，`r3`–`r5` 作为后端临时寄存器。调用者逆序压入参数，使被调用者能按固定 FP 偏移读取第一个、第二个参数。

真实平台也把 calling sequence、寄存器用途、栈帧和参数传递写进 ABI；例如 [System V AMD64 ABI](https://refspecs.linuxfoundation.org/elf/x86_64-SysV-psABI.pdf)。XMachine 的具体寄存器分配不同，但“双方必须共享一份协议”的原因相同。

## 3. 函数序言与尾声在做什么

典型函数进入时：

```text
push old_fp
fp = sp
sp -= local_frame_size
```

离开时反向恢复：

```text
sp = fp
pop fp
ret
```

有了稳定 FP，本函数中的参数和局部栈槽可以用固定偏移访问，即使执行中 SP 因临时 PUSH/CALL 改变也不受影响。

所有 return 路径必须执行同样清理，因此后端通常让 return 设置 `r0` 后跳到共享 epilogue，而不是在每个 AST/XIR return 位置复制一套恢复代码。

## 4. 为什么每个 XIR value 都分配 8 字节栈槽

真实编译器会做 liveness analysis 和 register allocation，让活跃值尽量驻留物理寄存器。阶段 6 采用更直接的策略：每个 XIR value 在当前函数栈帧中拥有固定 8 字节槽。

生成一条二元运算时：

1. 从左值栈槽加载到临时寄存器；
2. 从右值栈槽加载到另一临时寄存器；
3. 执行机器运算；
4. 把结果写回目标 value 的栈槽。

优点是正确性容易推导，不需要处理寄存器冲突、spill 决策和活跃区间。缺点是指令多、栈帧大、内存访问频繁。它是正确的 baseline，不是性能终点。

## 5. 指令选择怎样对应 XIR

多数算术 XIR 可映射为 load-operate-store。比较与控制流则使用阶段 4 的 flags：先发出 `CMP`，再根据 XIR 条件选择条件跳转。

Basic block label 在机器码缓冲区中获得偏移。同一函数内的已知跳转可在函数布局确定后回填；函数调用、全局地址、字符串地址等跨 section/跨 object 引用则不能由编译器独自决定。

这种差异可概括为：

- 编译时已知“目标在当前 text 中的相对位置”——本地 fixup；
- 只知道“目标符号叫什么”——输出 relocation 交给 linker。

## 6. `XObject` 为什么要包含四类信息

`XObject` 至少包含：

1. `text`：机器指令字节；
2. `data`：全局值、字符串等数据字节；
3. `symbols`：名字、所在 section、偏移、可见性/定义状态；
4. `relocations`：哪一处字节需要用哪个符号地址修补。

只保存机器码不够。若 `CALL foo` 中的 `foo` 在另一个文件，目标文件必须把“此处需要 foo 的地址”保留下来。否则链接器只能反汇编并猜测哪些常数是地址，既不可靠也无法区分普通整数。

ELF 规范同样把 section、symbol table 和 relocation 建模为目标文件核心信息；重定位项会关联需要修改的位置和用于计算的符号。参见 [System V ABI: ELF](https://refspecs.linuxfoundation.org/elf/elfspec.pdf)。XO01 不是 ELF，但采用相同问题分解。

## 7. `ABS32` 与 `ABS64` 表达什么

重定位类型告诉链接器要写多少位以及使用什么公式。阶段 6 的绝对重定位最终近似计算：

```text
patchedValue = symbolAddress + addend
```

`ABS32` 写四字节，常用于机器指令中的地址字段；`ABS64` 写八字节，常用于数据中的指针槽。链接器还必须检查结果能否放入目标宽度，并按 XMachine little-endian 写入。

addend 让“符号起始地址再偏移若干字节”无需虚构新符号。例如引用字符串内部位置可表示为 `stringSymbol + offset`。

## 8. 符号为什么区分 local 与 global

局部符号只在当前 object 中有意义，不应与另一个 object 的同名局部标签冲突。全局符号可被其他 object 引用，因此链接器必须合并其命名空间，并检测重复定义。

函数内部 basic block label 通常是 local；对外公开的函数名与全局变量通常是 global。可见性不是美观属性，而是符号解析算法的输入。

## 9. `XObjectIO` 为什么要自定义严格格式

XO01 是教学型二进制容器。`XObjectIO` 将内存模型稳定地序列化：magic/version、section 长度、字节内容、符号和重定位。

这里存在两种端序，不能混淆：

- XO01 元数据由 Java DataInput/DataOutput 风格按固定大端读取；
- `text`/`data` 中的 XMachine 整数和待补丁字段遵循目标机小端。

容器端序与载荷端序可以不同，只要格式明确。读取器主动验证 magic、版本、数量、长度、枚举编号和截断输入，避免恶意或损坏文件诱发巨大分配或宿主异常。

确定性输出也很重要：相同输入应产生相同字节顺序，这使测试、缓存和二进制 diff 可用。

## 10. `CompileResult` 为什么保留中间结果

编译入口不仅返回 XObject，还保留诊断、AST/类型结果或 XIR 等可观察信息。这样 CLI 可以提供 `--emit-xir`、测试可以精确断言某一层，而无需重复运行或侵入内部类。

一条工具链若只返回“成功/失败 + 最终字节”，遇到错误时很难判断是解析、类型、lowering 还是后端出了问题。

## 11. 当前后端的边界与代价

- 没有寄存器分配和机器级优化；
- 每个 value 固定 8 字节，牺牲空间与速度；
- 目标格式是 XO01，不兼容系统 ELF 工具；
- relocation 种类很少；
- ABI 是 XMachine 专用，不等同于主机 ABI。

这些限制让 calling convention、stack frame、symbol 与 relocation 四个核心概念保持可见。

## 12. 建议你亲手验证

1. 编译一个带两个参数的函数，画出 CALL 前后栈布局。
2. 根据后端分配表计算 frame size，再与序言中的 SP 调整对照。
3. 编译跨函数调用，检查 text 中的占位值与 relocation。
4. 将 XO01 写出再读回，比较 symbols/relocations 与原对象。
5. 截断目标文件或破坏 magic，观察读取器是否给出格式错误。

## 13. 学完本阶段应该能回答

- 为什么 CALL/RET 不等于完整调用约定？
- 固定栈槽方案牺牲了什么，又简化了什么？
- 编译器为何不能填入所有函数和全局变量的最终地址？
- symbol 与 relocation 各自保存哪一半信息？
- 容器端序为何可以不同于目标机器端序？

下一阶段的 xld 会合并多个 XO01，决定最终布局，解析符号并真正应用这些重定位。
