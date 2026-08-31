# 阶段 9 源码实现分析：类型如何变成真实的字节布局

> 对应提交：`阶段9：实现聚合类型、指针与布局可视化`
>
> 建议按 `Ast/Parser → TypeChecker → LayoutEngine → Lowerer/Xir → XBackend → XMachine`
> 的顺序阅读源码。

## 1. 本阶段真正解决的问题

在 P8 以前，所有运行时值基本都能塞进一个 64 位寄存器或栈槽。struct 和数组改变了
这个前提：一个值可能包含许多字节，字段有不同对齐要求，表达式既可能需要“读出值”，
也可能需要“取得存放值的地址”。

因此 P9 不是单纯给 Parser 增加几个节点，而是让以下各层共享同一个内存契约：

```text
源码类型
  -> 语义 Type
  -> LayoutEngine(size / alignment / offset)
  -> XIR 地址与内存操作
  -> 后端栈槽/全局区/访问宽度
  -> XMachine 字节与 64 位 load/store
```

只要其中一层另算一套偏移，程序就会“类型检查通过但读错字段”。所以布局算法必须成为
唯一事实来源，而不能散落在类型检查器、后端和 CLI 中。

## 2. 为什么 `Type` 从 enum 变成密封类型族

P8 的 `int/bool/void/string/error` 是有限集合，enum 足够。P9 的 `*T` 与 `[N]T`
却能递归组合，用户还能声明任意多个具名聚合，因此类型集合不再有限。

新的 `Type` 是 sealed interface：primitive 仍由 enum 表达，pointer/array 用 record
表达结构，aggregate 用具有名义身份的对象表达。两个字段完全相同但名字不同的 struct
仍是不同类型，这叫 nominal typing。

聚合先登记名字、再补字段，允许 `struct Node { next: *Node; }`。但是
`struct Bad { self: Bad; }` 没有有限大小，`LayoutEngine` 用 active 集检测这种按值环。
指针本身固定 8 字节，所以经过指针的递归不会继续展开目标布局。

## 3. struct、union 与 padding 怎样计算

对 struct，算法维护当前 cursor：每个字段先把 cursor 向字段 alignment 对齐，再记录
offset，最后增加字段 size；全部字段结束后再向最大 alignment 对齐总大小。

```text
struct Example { flag: bool; value: int; }

offset 0: flag   (1 byte)
offset 1: padding (7 bytes)
offset 8: value  (8 bytes)
size=16, align=8
```

union 不顺序累加：所有字段 offset 都是 0，size 取最大字段大小后再对齐。数组的 stride
等于元素布局大小，因此第 i 个元素地址是 `base + i * sizeof(element)`。

这与 C 对结构/联合的核心要求一致：结构成员按声明顺序排列并允许中间/尾部 padding，
union 成员重叠；`sizeof` 的结果包含必要 padding。可对照 ISO C11 草案
[N1570 §6.7.2.1、§6.5.3.4](https://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf)。
XLang 的具体基础类型大小是自身 ABI，不照搬某台主机。

## 4. 为什么类型检查要区分“值”和“可寻址位置”

`x + 1` 只需要 x 的值；`&x`、`x = 1` 则需要 x 的存储位置。Name、`*pointer`、
`array[index]`、`object.field` 都是 lvalue；普通算术结果不是。

`assignableTargetType` 集中判断这一性质。`&` 只接受 lvalue，`*` 只接受 pointer，索引
要求整数下标，成员访问会先剥掉一层可选指针再查字段。这样 Parser 只负责形状，真正的
可寻址语义仍由 TypeChecker 决定。

`null` 使用独立的临时语义类型，而不是随便当成整数 0；赋值兼容规则只允许它进入
pointer。`*void` 作为通用指针能与其他指针兼容，并以 1 字节作为算术步长。

## 5. `sizeof` 为什么在语义阶段确定

`sizeof(T)` 不执行 T 的任何表达式，它只查询 ABI。Parser 保存 TypeRef，TypeChecker
先把它解析成 Type 并验证布局，Lowerer 再把最终 size 变成整数常量。

这避免后端重新解释源码类型，也保证 CLI `layout` 与程序内 `sizeof` 得到同一答案。

## 6. XIR 为什么新增显式内存指令

原有 XIR 的 `Copy/Binary` 主要描述标量数据流。P9 增加：

- `Allocate`：为聚合申请对齐的栈区域；
- `GlobalAddress/AddressOf`：得到符号或局部槽地址；
- `PointerOffset`：按字节计算派生地址；
- `Load/Store`：在地址与标量值间转换；
- `MemCopy`：复制完整聚合对象。

Lowerer 有两条互补路径：`expression` 求 rvalue，`lvalueAddress` 求存储地址。成员访问
先通过布局表取得字段 offset，再生成 PointerOffset；索引先把 index 乘 element size。
这种分离避免把 `pair.x` 在读、写、取址三个上下文中各写一套不一致逻辑。

## 7. 后端为什么不把 struct 塞进寄存器

XMachine 寄存器宽 64 位，而聚合大小任意。本阶段 ABI 因此用地址代表聚合：局部聚合
拥有真实栈区域，XIR 中流动的是其地址；聚合参数按引用传递，聚合复制展开为 memcopy。

后端的 frame builder 按每个槽的 size/alignment 累积偏移，而不再假设“每值 8 字节”。
聚合字面量先清零，再逐字段写入，使 union 未覆盖字节与 padding 的行为确定、便于测试。

直接返回指向被调用者栈帧的聚合地址会悬空，所以当前类型检查器拒绝按值聚合返回，要求
显式返回指针。工业 ABI 会规定小聚合寄存器返回或 hidden sret pointer；例如
[System V AMD64 ABI](https://refspecs.linuxfoundation.org/elf/x86_64-SysV-psABI.pdf) 有完整的
参数分类规则。本项目先选择最容易观察的按引用教学 ABI。

## 8. 为什么 VM 需要 `LOAD8/STORE8`

若 bool 布局为 1 字节却仍执行 STORE64，写 `packet.ready` 会覆盖之后的 padding，甚至
相邻字段。后端根据 LayoutEngine 选择访问宽度，VM 则提供真正的单字节指令。

权限检查仍逐次经过 XOS 页表，因此新增宽度没有绕过 P5 的内存保护边界。

## 9. `layout` CLI 如何复用编译器

`Xlangc.layout` 不维护第二套类型解析器。对于 `[4]int`，它构造一个只含该参数类型的
合成函数并走正常 parse/check；对于 inline struct/union，则把声明与最小 main 一起
检查。成功后调用同一个 LayoutEngine 打印结果。

这样 CLI、`sizeof` 与机器代码布局不可能因三套独立 parser/算法逐渐漂移。

## 10. xrt 为什么改成 `*void`

P8 的 malloc 返回 int 只是指针类型尚不存在时的过渡。P9 中 `malloc/free/write` 和
内存 intrinsic 改用 `*void`，应用不能再无意中把地址参与普通整数运算。分配器内部确实
需要做地址记账时，必须写出 `as int`，把危险边界暴露在源码中。

## 11. 当前边界与下一步

- 无自动 bounds check，错误地址由 XOS page fault 捕获；
- union 不跟踪当前活跃字段；
- aggregate 参数按引用传递，不实现完整工业 ABI 分类；
- aggregate 返回必须使用 pointer；
- 没有堆对象生命周期和 borrow checker。

这些限制保留了 C 风格内存模型最关键的可见部分：地址、步长、字段偏移、padding、别名
和显式复制。

## 12. 建议你亲手验证

1. 用 `layout` 调换 bool/int 字段顺序，观察 padding 和总大小变化。
2. 比较同字段 struct 与 union 的 offset/size。
3. 打印数组索引的 XIR，找到 index 乘 element size 的指令。
4. 对 `&value`、`*pointer`、`pointer + 1` 分别追踪 AddressOf、Load 和缩放。
5. 写一个按值递归 struct，再改为 `*Node`，比较诊断。
6. 在 VM trace 中找到 LOAD8/STORE8，验证 bool 不覆盖相邻 int。
