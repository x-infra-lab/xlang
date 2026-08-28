# 阶段 5 源码实现分析：XOS、页表、内存保护与 `write` syscall

> 对应提交：`阶段5：实现 XOS 虚拟内存与 write syscall`
>
> 核心源码位于 `xlangvm/src/main/java/com/xlang/vm/`，重点阅读 `XOS`、`PageTable`、`Protection`、`Access` 与 `PageFault`。

## 1. 为什么平坦 byte 数组不够

阶段 4 中，程序地址几乎等同于 Java byte 数组下标。这样无法表达三个系统问题：

1. 程序使用的虚拟地址怎样对应物理内存；
2. 代码为何可执行但不可写，数据为何可读写但不可执行；
3. 用户程序如何请求输出、扩展堆等受控服务。

阶段 5 在 CPU 和物理内存之间加入 XOS：

```text
CPU 发起虚拟地址访问
       |
       v
XOS 根据 Access=READ/WRITE/EXECUTE 检查
       |
       v
PageTable 查映射与 Protection
       |
       v
物理 frame + 页内偏移
       |
       v
byte[] physical memory
```

Linux 内核文档也将页表描述为虚拟地址与物理地址之间的映射层级，并指出未映射访问会触发 page fault。参见 [Linux Kernel: Page Tables](https://www.kernel.org/doc/html/latest/mm/page_tables.html)。XOS 使用单级表，是这个思想的教学化版本。

## 2. 地址翻译为什么拆成“页号 + 页内偏移”

设页大小为 `PAGE_SIZE`：

```text
virtualPage = virtualAddress / PAGE_SIZE
offset      = virtualAddress % PAGE_SIZE
physical    = frameNumber * PAGE_SIZE + offset
```

页表只需记录 virtual page 到 physical frame 的映射，而不是为每个字节存一条记录。连续页内的所有地址共享映射与权限。

本项目故意使用 256 字节的小页，让测试和十六进制输出容易观察。真实系统常用更大的页；页大小属于该虚拟平台的设计选择，不能把教学常量误认为现实标准。

## 3. `PageTable` 与物理 frame 分配

`PageTable` 的职责是映射、查询、修改保护和解除映射。XOS 另行管理哪些物理 frame 可用：映射新虚拟页时分配 frame，解除映射时可回收。

把“虚拟页映射到哪里”和“哪个物理 frame 空闲”分开很重要：前者是地址空间视图，后者是物理资源管理。一个真实系统还要处理共享页、换页、写时复制等；本阶段采用单进程、立即分配的简单模型。

## 4. 为什么每次访问都携带 `Access`

相同地址在不同操作下可能是否合法不同：代码页允许 READ/EXECUTE，但不允许 WRITE；数据页允许 READ/WRITE，但不允许 EXECUTE。

`Access` 枚举把访问意图明确传给翻译函数，`Protection` 用位组合表达 R/W/X。翻译必须同时验证：

- 页面已映射；
- 页面拥有所需权限；
- 整个访问宽度没有跨进无效页面；
- 算出的物理范围有效。

失败抛出 `PageFault`，并携带虚拟地址、访问类型和原因。这样“未映射”和“写只读代码页”不会被混成普通数组越界。

### 为什么取指使用 EXECUTE 而不是 READ

CPU 读取 opcode 的动作在物理上也是读字节，但安全语义是 execute。若用 READ 检查，数据页只要可读就可当代码运行，R/W/X 保护会失去意义。

## 5. `XMachine` 如何接入 XOS

CPU 的寄存器仍保存虚拟地址。取指、读数据、写数据不再直接索引物理数组，而是统一经 XOS 翻译。

这是一个关键不变量：不能只改普通 LOAD/STORE，却让 PUSH、CALL 或取指绕过页表。任何漏掉的路径都会成为权限旁路。集中内存访问 helper 能减少这种风险。

启动时 XOS 建立最小地址空间：

- 代码区域映射为可读可执行；
- 栈区域映射为可读可写；
- 堆从 program break 开始；
- `mmap` 使用独立的虚拟区域。

## 6. `brk` 与 `mmap` 为什么代表两种堆获取方式

program break 是进程数据段末端的位置，向上移动可扩展一段连续堆；`mmap` 则在另一虚拟区建立映射。

Linux 的 [`brk(2)`](https://man7.org/linux/man-pages/man2/brk.2.html) 描述了通过改变 program break 调整数据段，Linux 的 [`mmap(2)`](https://man7.org/linux/man-pages/man2/mmap.2.html) 则描述在进程虚拟地址空间创建新映射。XOS 借用这些接口概念，但参数、页大小和行为是简化版，不能视为 Linux ABI 的逐字复制。

## 7. syscall 为什么是用户程序与 XOS 的边界

用户代码不能直接调用 Java 的标准输出，因为那会绕开目标平台；它通过约定的 syscall 指令和寄存器提交编号与参数。XOS 分派编号，验证用户地址，然后在宿主环境执行受控操作。

阶段 5 的 `write` 大致需要：

1. 从约定寄存器读取文件描述符、buffer 虚拟地址和长度；
2. 按用户 READ 权限逐字节读取 guest memory；
3. 写到 XOS 管理的输出；
4. 把实际写入数量放回返回值寄存器。

POSIX/Linux `write` 的核心契约也是“从 buffer 写最多 count 字节，并返回实际数量或错误”，参见 [`write(2)`](https://man7.org/linux/man-pages/man2/write.2.html)。XOS 目前只实现目标平台所需子集。

### 为什么 syscall 不能相信用户指针

寄存器中的地址来自用户程序，可能未映射、越界或不可读。XOS 必须经页表访问，不能把它直接当宿主 Java 数组下标。否则 guest 程序可以越过自己的地址空间，甚至让宿主异常泄漏出来。

## 8. 内核写入与用户写入为什么权限不同

加载器或内核有时需要初始化一个最终对用户只读的页面。XOS 的内核写 helper 可以绕过用户 WRITE 权限，但仍要求页面存在且物理范围合法。

这不是“权限失效”，而是主体不同：页面保护约束 guest CPU 的用户访问；内核为建立进程映像拥有更高权限。关键是旁路只能封装在明确命名的内核路径里，不能成为普通指令可调用的后门。

## 9. `PageFault` 应该在哪里产生

页面错误应在地址翻译边界产生，而不是让每个 opcode 各自猜测。集中后：

- LOAD、STORE、PUSH、取指获得一致语义；
- 故障包含原始虚拟地址；
- 将来更换多级页表时，CPU 指令实现不变。

这种设计就是 abstraction barrier：上层知道“按某权限访问虚拟地址”，不需要知道 frame 号和物理数组布局。

## 10. 当前虚拟内存与真实 OS 的距离

阶段 5 有意不实现：

- 多级页表与 TLB；
- demand paging 和磁盘换页；
- 多进程地址空间；
- copy-on-write 与共享页；
- 异步中断和进程调度；
- 完整 POSIX syscall 集。

它保留了理解系统软件最关键的骨架：虚拟地址、页映射、R/W/X、fault、堆边界和 syscall 信任边界。

## 11. 建议你亲手验证

1. 映射一个 R-X 页，尝试取指、读和写，比较结果。
2. 访问未映射地址，检查 PageFault 是否报告虚拟地址和 Access。
3. 让一个多字节访问跨过页边界，其中第二页未映射。
4. 调用 write 时传入非法 guest buffer，确认不能变成 Java 数组异常。
5. 调整 program break，画出 code/data/heap/mmap/stack 的虚拟地址布局。

## 12. 学完本阶段应该能回答

- 虚拟页和物理 frame 有什么区别？
- 为什么取指必须检查 EXECUTE 权限？
- CPU 中的地址为何仍是虚拟地址？
- syscall 为什么必须重新验证用户传入的指针？
- 内核初始化写与用户 STORE 为什么可以拥有不同权限？

下一阶段会把 XIR 真正翻译成 XMachine 指令，并把尚未知晓的地址记录成重定位，生成可被链接器处理的目标文件。
