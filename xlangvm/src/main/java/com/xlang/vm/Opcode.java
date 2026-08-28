package com.xlang.vm;

import java.util.HashMap;
import java.util.Map;

/** P4 instruction opcodes and encoded sizes. */
public enum Opcode {
    HALT(0x00, 1, "halt"), NOP(0x01, 1, "nop"),
    MOVI(0x10, 10, "movi"), MOV(0x11, 3, "mov"),
    ADD(0x20, 4, "add"), SUB(0x21, 4, "sub"), MUL(0x22, 4, "mul"),
    DIV(0x23, 4, "div"), MOD(0x24, 4, "mod"), CMP(0x30, 3, "cmp"),
    JMP(0x31, 5, "jmp"), JZ(0x32, 5, "jz"), JNZ(0x33, 5, "jnz"),
    JN(0x34, 5, "jn"), LOAD64(0x40, 3, "load64"), STORE64(0x41, 3, "store64"),
    PUSH(0x50, 2, "push"), POP(0x51, 2, "pop"),
    CALL(0x60, 5, "call"), RET(0x61, 1, "ret"),
    SYSCALL(0x70, 1, "syscall");

    private static final Map<Integer, Opcode> BY_CODE = new HashMap<>();
    static { for (Opcode opcode : values()) BY_CODE.put(opcode.code, opcode); }

    private final int code;
    private final int size;
    private final String mnemonic;
    Opcode(int code, int size, String mnemonic) {
        this.code = code; this.size = size; this.mnemonic = mnemonic;
    }
    public int code() { return code; }
    public int size() { return size; }
    public String mnemonic() { return mnemonic; }
    public static Opcode fromByte(int code) { return BY_CODE.get(code); }
}
