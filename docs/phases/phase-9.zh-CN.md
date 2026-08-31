# 阶段 9 — 聚合类型与显式内存布局

**当前阶段：** P9。

P9 让此前预留的指针、数组、成员访问、`sizeof` 和转换语法真正可用，并增加
具名 struct/union 声明及聚合字面量。

## 语法

```xlang
struct Packet { ready: bool; code: int; samples: [3]int; }
union Value { integer: int; flag: bool; }

fn main() -> int {
    let packet = Packet { ready: true, code: 40, samples: [1, 2, 3] };
    let pointer: *int = &packet.code;
    *pointer += packet.samples[1];
    return sizeof(Packet);
}
```

`null` 可赋给指针；数组和指针都能索引；聚合值及其指针都能使用成员访问。
指针算术按目标类型大小缩放，`*void` 则按字节移动。整数与指针之间必须用 `as`
显式转换。

## 布局 ABI

| 类型 | 大小 | 对齐 |
|------|-----:|-----:|
| `bool` | 1 | 1 |
| `int`、`string`、`*T` | 8 | 8 |
| `[N]T` | `N * sizeof(T)` | `alignof(T)` |

struct 按声明顺序逐字段对齐，并按最大字段对齐量补齐尾部；union 的全部字段都在
偏移 0 重叠，其大小是最大字段大小向最大对齐量取整。按值递归聚合会被拒绝，经过
指针的递归则具有有限布局。

## 降级与机器支持

类型检查器会保留已解析的类型引用和名义聚合定义。`LayoutEngine` 是唯一布局事实
来源，`sizeof`、字段偏移、全局区、栈分配、聚合拷贝与 CLI 都使用它。

XIR 新增分配、取址、字节偏移、load、store 和 memcopy。教学 ABI 用地址表示聚合
值；聚合参数按引用传递，聚合返回需要使用指针。后端会建立对齐的栈区域、将聚合
字面量清零，并按宽度访问内存。XMachine 新增 `LOAD8`/`STORE8`，保证 1 字节 bool
不会覆盖相邻字段。

xrt 的 `malloc/free/write` 及内存 intrinsic 现已使用 `*void`，不再把公开指针
伪装成普通整数；分配器内部需要做地址记账时使用显式 `as int` 转换。

## CLI

```bash
./gradlew :xlang-cli:run --args="layout '[4]int'"
./gradlew :xlang-cli:run --args="layout 'struct Packet { ready: bool; code: int; }'"
```

输出会列出总大小、对齐、字段偏移和 padding 区域。

## 验证

测试覆盖解析、名义类型检查、非法初始化、递归布局拒绝、struct padding、union
重叠、数组步长、pointer/null 规则、XIR 内存操作、聚合程序实际执行、字节访问、
layout CLI，以及使用指针类型的 xrt ABI。

## 下一阶段

P10 将提供贯穿 P1–P9 全部工具链的综合示例程序。
