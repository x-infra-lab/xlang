# Phase 5 — XOS virtual memory

**Current phase:** P5.

P5 inserts an operating-system layer between the P4 CPU and physical RAM.
Addresses held by registers are now virtual: instruction fetches, loads,
stores, stack traffic, and syscall buffers all pass through the same page table.

## Address-space layout

| Virtual range | Purpose | Initial protection |
|---------------|---------|--------------------|
| `0x00000000...` | loaded program | `r-x` |
| `0x00010000...` | `brk` heap | unmapped, then `rw-` on growth |
| `0x00040000...` | anonymous `mmap` area | caller-selected |
| `0x000ffc00–0x000fffff` | four-page stack | `rw-` |

Pages are 256 bytes so a debugger can display several translations without a
wall of output. Physical frames are allocated independently from virtual page
numbers, making the translation visible rather than an identity-map shortcut.

## Page table and protection

Each mapping records a virtual page, physical frame, `rwx` bits, and a readable
region name. The same translator enforces:

- execute permission for instruction fetch;
- read permission for loads and syscall input;
- write permission for stores and stack pushes;
- mappings across page boundaries, byte by byte.

Unmapped or disallowed accesses raise a `PageFault` containing the faulting
instruction, virtual address, requested access, mapping name, and protection.
Unknown opcodes and other P4 machine faults remain separate and deterministic.

## Heap and anonymous mappings

`XOS.brk(0)` queries the current break. Growing it allocates enough `rw-` pages;
shrinking it releases complete pages and physical frames. `XOS.mmap` chooses a
page-aligned address, rounds the requested length up, and applies caller-selected
protection. `mprotect` changes mapped-page permissions for tests and later loaders.

```bash
./gradlew :xlang-cli:run --args="mem show"
./gradlew :xlang-cli:run --args="mem map 512 r--"
```

The visualiser prints coalesced virtual ranges, protection bits, physical frame
numbers, the current break, and used/free physical-page counts.

## First syscall

Opcode `70` invokes `syscall` using this register ABI:

| Register | Meaning |
|----------|---------|
| `r0` | syscall number (`1` = `write`) and return value |
| `r1` | file descriptor (`1` stdout, `2` stderr) |
| `r2` | virtual buffer address |
| `r3` | byte length |

`write` reads the guest buffer through page-table protection, appends its bytes
to observable machine output, and returns the number of bytes in `r0`. The CLI
prints that output before the final register dump.

## Verification

```bash
./gradlew build
./gradlew phaseInfo
```

Tests cover boot mappings, execute/read/write protection, unmapped faults,
cross-page 64-bit values, heap growth and shrink, mmap/mprotect, frame exhaustion,
write output and return values, invalid syscalls, map visualisation, and all P4
machine behavior under translated memory.

## Next

P6 translates typed XIR into this ISA and emits `.xo` object files, connecting
the compiler front end to the machine for the first time.
