# Phase 9 — aggregate types and explicit memory layout

**Current phase:** P9.

P9 makes the reserved pointer, array, member, `sizeof`, and cast syntax real,
and adds named struct/union declarations plus aggregate literals.

## Syntax

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

`null` is assignable to pointers. Indexing accepts arrays and pointers; member
access accepts an aggregate or pointer to one. Pointer arithmetic is scaled by
the pointed-to layout, while `*void` arithmetic is byte-based. Integer/pointer
conversion is explicit through `as`.

## Layout ABI

| Type | Size | Alignment |
|------|-----:|----------:|
| `bool` | 1 | 1 |
| `int`, `string`, `*T` | 8 | 8 |
| `[N]T` | `N * sizeof(T)` | `alignof(T)` |

Struct fields are aligned in declaration order and tail-padded to the largest
field alignment. Union fields overlap at offset zero; union size is the aligned
maximum field size. Recursive by-value aggregates are rejected, while recursive
pointers are finite and valid.

## Lowering and machine support

The type checker records resolved type references and nominal aggregate
definitions. `LayoutEngine` is the single source of truth shared by `sizeof`,
field offsets, global allocation, stack allocation, copies, and the CLI.

XIR gains explicit allocation, address, byte-offset, load, store, and memcopy
instructions. Aggregate values are represented by addresses in the teaching
ABI. Aggregate arguments are passed by reference; aggregate returns must use a
pointer. The backend creates aligned stack regions, zero-initializes literals,
and emits width-aware loads/stores. XMachine adds `LOAD8`/`STORE8` so a one-byte
`bool` does not overwrite adjacent fields.

xrt now exposes `malloc/free/write` and raw memory intrinsics with `*void`
instead of untyped integer addresses. Its allocator still performs address
bookkeeping as integers only behind explicit casts.

## CLI

```bash
./gradlew :xlang-cli:run --args="layout '[4]int'"
./gradlew :xlang-cli:run --args="layout 'struct Packet { ready: bool; code: int; }'"
```

The output reports total size, alignment, field offsets, and padding regions.

## Verification

Tests cover parsing, nominal type checking, invalid initializers, recursive
layout rejection, struct padding, union overlap, array stride, pointer/null
rules, XIR memory operations, executable aggregate programs, byte loads/stores,
the layout CLI, and the pointer-typed xrt ABI.

## Next

P10 adds the capstone application that exercises the complete P1–P9 stack.
