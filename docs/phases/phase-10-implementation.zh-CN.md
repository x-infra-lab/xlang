# 阶段 10 源码实现分析：怎样证明九个阶段真的是一条工具链

> 对应提交：`阶段10：完成端到端工具链综合示例`
>
> 建议同时打开 `examples/capstone.xl`、`Xlangc`、`XBackend`、`Xld`、`Xrt`、
> `xrt.xl`、`XExecutable`、`XMachine` 与 `XOS`，沿数据形态变化阅读。

## 1. P10 为什么不是再加一个编译器功能

P1–P9 分别证明了局部能力，但“每层单测都绿”不等于“组合后能工作”。编译器项目最危险
的错误常在边界：前端认为 pointer 是 8 字节，后端若按 4 字节缩放；编译器留下一个
符号，链接器若用另一种名字；runtime 遵循一套调用约定，调用方若按另一套压参，单个
模块内部都可能看似正确，组合后才失败。

所以 P10 的新产物是 integration invariant：版本库中的同一份 XLang 源码必须从字符
一路变成可观察的 syscall。它不是绕开 CLI 的 Java 演示，也不是测试中的内嵌字符串；
`examples/capstone.xl` 同时是给人运行的示例和自动化测试读取的输入。

这种测试层次符合测试金字塔的一般思想：大量单元测试定位局部规则，少量端到端测试确认
真实边界能够组合。端到端测试数量不必多，但必须穿过真正的生产路径。

## 2. 一份源码经过了哪些表示

```text
UTF-8 文本
  -> Token 列表                         P1 Lexer
  -> Ast.Program                        P1 Parser
  -> 带 TypeCheckResult 的 AST          P2 TypeChecker
  -> Xir.Module / BasicBlock / Value    P3 Lowerer
  -> XO01(text/data/symbol/relocation)  P6 XBackend
  +  XO01(xrt)                          P8 Xrt.object
  -> XE01(entry/text/data/symbol)       P7 Xld
  -> 虚拟页 + CPU 状态                  P4/P5 loadInto
  -> write/brk/exit 事件                 P8 XOS syscall
```

重点不是类名，而是每次转换都减少一种不确定性：Lexer 确定词边界；Parser 确定语法
结构；TypeChecker 确定名字与类型；Lowerer 把结构化控制流和隐式内存语义显式化；后端
选择具体指令但保留未决地址；链接器最后确定地址；VM 才赋予这些字节执行含义。

LLVM Language Reference 把 IR 描述为编译器各阶段之间的通用低层表示；ELF 规范把目标
文件中的 section、symbol 与 relocation 定义成独立概念。XIR/XO01 的规模小得多，但
采用相同的职责分离思想。可对照 [LLVM Language Reference](https://llvm.org/docs/LangRef.html)
和 [System V ABI Chapter 4: Object Files](https://refspecs.linuxfoundation.org/elf/gabi4+/ch4.intro.html)。

## 3. `capstone.xl` 为什么这样设计

示例不能只写 `printf("hello")`，因为那主要覆盖 P8。这里刻意让每段源码承担一项验收：

- `struct Report` 的 bool 后有 padding，验证布局不是简单字段大小相加；
- `[4]int` 与 `values[index]` 验证数组 stride 和地址计算；
- `&report.values[0]` 到 `sum(*int, int)` 验证指针参数与跨函数 ABI；
- `while` 验证基本块、branch 和回边；
- `union Reading` 验证聚合字面量、重叠布局与 memcopy；
- `malloc(sizeof(Report)) as *Report` 把静态布局和动态堆分配连接起来；
- `printf` 与 `free` 验证普通外部调用经链接解析到 xrt；
- `return 0` 由 `start` 转为 `exit(0)`，验证入口与进程退出协议。

四个数组元素是 10、11、12、9，总和固定为 42。结果不是只打印出来，还先在 guest
程序内部比较；任何字段偏移、指针缩放、循环或调用约定错误都会走到状态 71，而不是
产生貌似合理的输出。

## 4. 为什么 `sizeof(Report)` 是 48

XLang P9 ABI 中 bool 的 size/alignment 为 1/1，int 为 8/8：

```text
offset  0..0   ready       1 byte
offset  1..7   padding     7 bytes
offset  8..39  values      4 * 8 bytes
offset 40..47  total       8 bytes
size = 48, alignment = 8
```

程序把 48 既传给 malloc，又通过 printf 输出。两处都来自同一个 `LayoutEngine`：前者
若少算会导致堆越界，后者若使用另一套算法会暴露不一致。C 结构同样允许成员间与末尾
padding，`sizeof` 包含这些字节；参见 ISO C11 草案
[N1570 §6.7.2.1、§6.5.3.4](https://www.open-std.org/jtc1/sc22/wg14/www/docs/n1570.pdf)。
XLang 只借鉴布局原则，基础类型 ABI 仍由自己定义。

## 5. 为什么测试要逐个调用 facade

测试依次调用 `Xlangc.lex/parse/check/lower/compile`，不是因为 `compile` 没调用前四步，
而是为了给失败提供阶段坐标。若直接 `compile`，只能知道整体失败；逐层断言则能立即区分
词法诊断、语法诊断、类型诊断、XIR 缺失或后端错误。

测试还检查 `Report/Reading` 出现在语义聚合表、XIR 至少存在 `PointerOffset`、XO01
导出 `main`。这些断言验证“关键机制确实被使用”，防止未来有人把示例简化后仍然只凭
最终字符串通过。

这不是要求所有项目都重复运行编译五次；P10 的输入很小，换取的是教学可定位性。生产
编译器的大型测试通常会保存或检查中间表示，目标也是让错误靠近其所属阶段。

## 6. 编译为什么不能提前知道最终地址

应用 XO01 会引用 `malloc`、`printf`、`free`，xrt XO01 会引用应用的 `main`。各自单独
编译时都不知道对方最终排在 text 的哪个位置，所以后端写入占位值并记录 relocation：
“在 section 的这个 offset，按这种宽度，把 symbol + addend 回填进来”。

`Xld.link` 合并 section、构建全局符号表、检查重复/未定义符号，再应用 relocation。
`--verbose` 输出逐字节回填，提供第二种可观察性：syscall trace 观察运行时边界，link
trace 观察装载前的地址绑定边界。

这正是传统静态链接的核心模型。ELF 只比 XO01 增加更多 section、机器类型和 relocation
种类，基本问题仍是把分散编译单元中的符号引用绑定到最终地址。

## 7. `start` 为什么必须来自 xrt

最终 executable 的 entry 是 `start`，不是 `main`：

```text
XE01 entry
  -> xrt.start()
     -> application.main()
     -> xrt.exit(status)
        -> __syscall
           -> XMachine SYSCALL
              -> XOS.exit
```

`main` 是语言层普通函数，正常 return 只恢复调用者；`exit` 是进程级动作，会记录状态并
停止虚拟 CPU。真实系统同样把程序入口、C runtime 启动和用户 main 区分开；只是本项目
把这条路径缩小到几行 XLang，使它能被逐步跟踪。

## 8. syscall 为什么是“可观察输出”的正确边界

guest 程序不能直接写宿主终端。它只能把 syscall 编号与参数放进约定寄存器，执行
SYSCALL；XOS 再检查虚拟地址和权限，执行服务并记录事件。

因此输出文本和事件日志证明了两件不同的事：文本证明程序行为符合预期，事件证明行为
确实穿过了 OS 边界而不是由编译器常量折叠或宿主 Java 偷偷打印。`brk` 证明 allocator
请求了堆空间，`write` 证明 printf 经 runtime 输出，最后的 `exit(0)` 证明启动协议闭环。

Linux 的 `write(2)` 同样由文件描述符、buffer 地址和长度描述一次写请求，可对照
[write(2)](https://man7.org/linux/man-pages/man2/write.2.html)。XOS 使用自定义 syscall
编号和虚拟地址空间，但保留了用户态请求、内核校验与结果返回的分层。

## 9. 综合测试发现的 2 KiB 栈问题

第一次运行并没有输出 42，而是在 `0x000ff7f8` 写入时 page fault。这个地址比原栈映射
下界再低 8 字节，说明不是随机野指针，而是规则的栈增长越过边界。

原因是当前后端以“可读性”换“空间”：每个 XIR value 都有独立 stack slot，聚合
literal 还需要实际对象区。`main` 已有较大 frame，调用 `printf -> print_int -> malloc`
时多个 frame 同时存活，累计超过原来的 8 页；XMachine 的页是 256 字节，因此旧容量
只有 `8 * 256 = 2048` 字节。

P10 把 `XOS.STACK_BYTES` 调整为 12 页，即 3072 字节。这不是隐藏 page fault：映射仍
有明确下界，越界仍会失败，只是资源预算与当前未优化后端生成的程序规模相匹配。另一条
长期路线是做 liveness analysis、栈槽复用或寄存器分配；它们会改变后端复杂度，不属于
“综合验收”阶段。

这个故障也说明 P10 的必要性：P9 的字段访问测试和 P8 的 printf 测试分别通过，只有把
二者嵌套到同一进程调用链，才暴露累计资源假设。

## 10. 为什么精确比较输出还要检查 syscall 集合

测试同时断言：

1. 输出严格等于两行预期文本；
2. 进程执行了 write 和 brk；
3. 最后一个事件是 exit，状态为 0；
4. verbose link trace 非空。

只检查输出可能接受绕过 allocator 的实现；只检查 syscall 名可能接受打印乱码；只检查
退出码又可能让程序什么也不做。组合断言从语言结果、OS 副作用和链接行为三个角度建立
证据。

## 11. 当前边界

- 综合程序验证一条代表性路径，不替代各阶段的错误用例和边界单测；
- syscall trace 逐字符显示 printf 的 write，故意强调可观察性而非效率；
- 教学后端尚无寄存器分配与栈槽复用；
- xrt allocator 不拆分、不合并空闲块，也不检测 double free；
- XLang/XO01/XE01 是教学格式，不宣称兼容 C/ELF 或真实硬件。

## 12. 建议你亲手跟一遍

1. 运行 `tokens` 和 `parse`，找到 `Report` 与 `Reading` 的 AST。
2. 运行 `layout 'struct Report { ready: bool; values: [4]int; total: int; }'`，验证 48。
3. 运行 `ir`，找到 index 乘 8、PointerOffset、Load、Store、MemCopy 与 while 回边。
4. compile 后查看 XO01 symbols/relocations 的相关测试或调试对象。
5. 用 `link --verbose` 找 `main`、`malloc`、`printf`、`free` 的回填。
6. 用 `syscall-trace` 区分 brk、write 和 exit。
7. 把数组元素 9 改成 10，观察 guest 自检走状态 71。

## 13. 学完 P10 应该能回答

- 为什么“所有模块单测通过”仍不等于工具链可用？
- 从源码到 syscall，中间每种表示消除了什么不确定性？
- 编译器为什么留下 relocation，而不是自己决定外部函数地址？
- `main return` 为什么必须由 runtime 转成 `exit`？
- 输出文本、link trace 与 syscall trace 分别证明哪一层？
- 为什么端到端程序能发现单阶段测试遗漏的栈容量假设？

P10 的终点不是“再也不用改”，而是建立了一条可信的回归主线：未来新增优化、ABI 或语言
功能时，只要这份综合程序仍以同样结果穿过真实路径，就能确认 P1–P9 的契约没有被拆散。
