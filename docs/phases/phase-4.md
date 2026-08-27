# Phase 4 — XMachine and XCPU

**Current phase:** P4.

P4 boots the first simulated computer in xlang. It does not run compiled xlang
source yet—that bridge arrives in P6—but it executes real encoded instructions
from RAM, one fetch/decode/execute step at a time.

## Machine model

- 64 KiB byte-addressed RAM by default, configurable in the Java API.
- Eight 64-bit registers `r0`–`r7`; `r7` is the downward-growing stack pointer.
- A byte-addressed program counter plus zero (`Z`) and negative (`N`) flags.
- Little-endian 32-bit jump targets and 64-bit immediates/memory values.
- The stack starts at the end of RAM. `call` pushes its return address and
  `ret` pops it through the same observable stack mechanism as `push` / `pop`.
- Arithmetic wraps naturally at 64 bits. `cmp` uses signed 64-bit ordering.

## P4 instruction encoding

| Opcode | Encoding after opcode | Meaning |
|-------:|-----------------------|---------|
| `00` | — | `halt` |
| `01` | — | `nop` |
| `10` | `dst:u8 imm:i64` | `movi` |
| `11` | `dst:u8 src:u8` | `mov` |
| `20`–`24` | `dst:u8 lhs:u8 rhs:u8` | `add`, `sub`, `mul`, `div`, `mod` |
| `30` | `lhs:u8 rhs:u8` | signed `cmp` and set flags |
| `31`–`34` | `target:i32` | `jmp`, `jz`, `jnz`, `jn` |
| `40`–`41` | `value-reg:u8 address-reg:u8` | `load64`, `store64` |
| `50`–`51` | `reg:u8` | `push`, `pop` |
| `60` | `target:i32` | `call` |
| `61` | — | `ret` |

Registers are encoded as `00`–`07`. Multi-byte fields are little-endian.

## Run a hand-assembled program

This program loads decimal 42 into `r0` and halts:

```text
10 00 2a 00 00 00 00 00 00 00 00
│  │  └────────── i64 42 ─────────┘
│  └─ r0
└─ movi                         halt ┘
```

```bash
./gradlew :xlang-cli:run --args="run '10 00 2a 00 00 00 00 00 00 00 00'"
./gradlew :xlang-cli:run --args="trace '10 00 2a 00 00 00 00 00 00 00 00'"
```

`run` prints all registers and flags after halt. `trace` additionally prints
the instruction address, encoded bytes, disassembly, next PC, `r0`, and flags
after every step.

## Faults and safety rails

The machine traps with the faulting instruction address on unknown opcodes,
truncated instructions, invalid registers, division/modulo by zero, out-of-RAM
access, invalid jump/return targets, stack underflow, and instruction-limit
exhaustion. The default one-million-step limit makes accidental infinite loops
safe to run from the CLI.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

Tests cover arithmetic loops, branches, flags, memory, stack, call/return,
manual stepping, trace formatting, hex parsing, every fault family, and both
CLI execution modes.

## Next

P5 adds XOS page tables, `mmap` / `brk`, protection checks, memory inspection,
and the first `write` syscall.
