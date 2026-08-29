# 阶段 7 源码实现分析：链接、符号解析、重定位与 XE01

> 对应提交：`阶段7：实现 xld 链接器与 XE01 可执行文件`
>
> 核心源码位于 `xld/src/main/java/com/xlang/linker/`，并需结合 `XExecutable.loadInto` 与 `XMachine` 的分段加载逻辑阅读。

## 1. 链接器为何不是“把文件拼起来”

若简单连接两个 object 的 text，第一个 object 增长后，第二个 object 中所有函数和数据地址都会改变。调用目标、全局变量指针和字符串地址都必须重新计算。

xld 的核心工作是：

```text
XO01 objects
   |
   +-- 布局：每个 text/data 放到最终哪里
   +-- 符号：每个名字最终地址是多少
   +-- 解析：每个引用应绑定哪个定义
   +-- 重定位：把地址写回占位字段
   +-- 启动：建立 entry 与初始化顺序
   v
XE01 executable
```

ELF 规范对 link editor 的描述同样包括组合输入文件、重新定位数据和解析符号引用，参见 [System V ABI: ELF](https://refspecs.linuxfoundation.org/elf/elfspec.pdf)。GNU gold 作者 Ian Lance Taylor 的系列也把 symbol resolution 与 relocation 作为理解链接器的主轴，参见 [Linkers part 2: Symbol resolution](https://www.airs.com/blog/archives/49)。

## 2. 为什么必须先布局，再应用重定位

一个 relocation 的结果依赖符号最终地址，而符号最终地址又依赖每个输入 section 的最终起点。因此顺序不能颠倒：

1. 为所有 object 计算 text/data 输出偏移；
2. 由 `sectionBase + symbolOffset` 算符号地址；
3. 解析 relocation 引用；
4. 按类型把计算结果补到输出字节。

如果边复制边立即打补丁，后方 section 尚未布局，forward reference 仍未知；强行这样做只会产生复杂的二次修正表。

## 3. text 与 data 为什么分段布局

最终映像把机器码与可写数据分开。data 起点按页面对齐，使页权限可以干净地设置为：

- text：R-X；
- data：RW-。

若两者共享同一页，就无法同时做到“代码可执行不可写”和“数据可写不可执行”。页面对齐因此既是地址计算要求，也是内存保护要求。

`XExecutable.loadInto` 不是把整个 `.xex` 文件原样复制到地址 0；它读取容器字段后，把 text 与 data 分别装入指定虚拟地址并设置权限。文件格式中的 header 是给加载器看的，不是 CPU 指令。

## 4. 局部符号与全局符号怎样解析

每个输入 object 先拥有自己的 local symbol map；所有可导出定义再进入 global map。

解析引用时：

1. 优先按当前 object 的局部定义解释；
2. 否则查询全局定义；
3. 找不到则报告 undefined symbol；
4. 两个 object 定义同名 global 则报告 duplicate symbol。

局部名字必须带 object 作用域，否则两个编译单元内部都叫某个临时 label 时会错误冲突。ELF 的 symbol binding 同样区分局部与全局可见性，参见 [System V gABI: Symbol Table](https://gabi.xinuos.com/elf/05-symtab.html)。

## 5. 重定位补丁如何计算

阶段 7 处理绝对重定位，核心公式是：

```text
S = resolved symbol virtual address
A = relocation addend
value = S + A
```

然后根据 `ABS32` 或 `ABS64` 检查范围，并按 XMachine little-endian 写入 relocation 指定的输出位置。

应用前还要验证：

- patch offset 属于其 section；
- 写入宽度不会越界；
- symbol 已定义；
- 计算无溢出且能装入目标宽度。

verbose relocation trace 会打印输入位置、符号、S、A 和补丁字节。它不是多余日志，而是回答“链接器到底写了什么”的证据链。

## 6. 为什么链接器要合成启动代码

机器只能从一个 entry address 开始执行，但语言语义还要求先初始化全局状态，再调用用户入口。

xld 合成的 startup 逻辑近似：

```text
call object_1.$module_init
call object_2.$module_init
...
call main
halt
```

这把多个编译单元的初始化顺序集中在最终全局视图中处理。编译单个 object 时并不知道将来会与哪些模块链接，因而编译器不适合独自生成完整启动序列。

阶段 8 引入 runtime 后，入口还会切换为 runtime 的 `start`，但“链接器决定最终 entry”这一职责不变。

## 7. `XE01` 为什么又需要一套严格格式

目标文件服务于 linker，可执行文件服务于 loader，两者包含的信息不同。XE01 保存加载与执行所需的最小信息，例如：

- magic/version；
- entry address；
- text 虚拟地址与字节；
- data 虚拟地址与字节。

已经应用完成的 relocation 与大部分内部 symbol 不必带入运行时。`XExecutableIO` 对 magic、长度、版本、截断和尾随数据进行验证，防止损坏文件被当作可信内存布局。

`.xo` 与 `.xex` 都是容器；它们的 magic `XO01`/`XE01` 让读取器在解析可变长度字段前先确认文件种类。

## 8. 分段加载怎样闭合阶段 5 的保护模型

阶段 5 已有 R/W/X 页权限，但只有当加载器把 text 和 data 分开映射，这些保护才真正发挥作用。

加载过程需要内核权限写入初始 text/data，完成后 guest 的取指走 EXECUTE、普通存储走 WRITE。于是：

- 修改 text 触发 page fault；
- 从 data 取指触发 page fault；
- 正常读取常量和修改全局变量仍可工作。

链接布局、可执行格式与虚拟内存权限在这里第一次形成完整闭环。

## 9. 一个跨文件调用的完整例子

`a.xo` 定义 `main` 并引用 `helper`；`b.xo` 定义 `helper`。

1. 编译 `a` 时，CALL 地址写占位值，并输出 `helper` relocation。
2. xld 把 `a.text`、`b.text` 放入输出 text，记录各自 base。
3. `helper` 的最终地址等于 `b.text base + helper offset`。
4. xld 在 `a` 的 CALL 地址字段写入该值。
5. VM 加载 XE01，CALL 直接跳到补丁后的机器地址。

这说明 relocation 不是运行时动态搜索；阶段 7 做的是静态链接，补丁在执行前已经完成。

## 10. 当前链接器的边界

- 静态链接，不实现动态库与运行时装载重定位；
- 不做 section garbage collection；
- 不做弱符号、版本符号与可见性高级规则；
- 只有 ABS32/ABS64；
- XE01 是项目私有格式，不可由系统 ELF loader 直接运行。

这些简化保留了任何链接器都绕不开的布局、解析和 relocation。

## 11. 建议你亲手验证

1. 编译两个互相引用的 object，先看 relocation，再看链接补丁。
2. 交换输入 object 顺序，观察布局地址变化但程序语义不变。
3. 制造 undefined 与 duplicate global，确认错误发生在链接期。
4. 检查 data 是否页面对齐以及加载后的 R/W/X。
5. 对比 `.xex` 文件开头与 VM 地址 0，理解 header 为何不被当成代码。

## 12. 学完本阶段应该能回答

- 链接器为什么不能只拼接字节？
- 布局、符号地址与 relocation 为何有严格先后关系？
- local symbol 为什么必须带 object 作用域？
- startup code 为什么由拥有全局视图的链接器合成？
- 可执行文件的文件偏移和加载后的虚拟地址有何区别？

下一阶段会加入 xrt：让应用通过稳定的库函数访问 syscall，并在用户空间实现最小分配器和格式化输出。
