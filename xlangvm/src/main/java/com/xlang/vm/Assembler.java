package com.xlang.vm;

import java.io.ByteArrayOutputStream;

/** Tiny byte emitter used to hand-assemble P4 examples and tests. */
public final class Assembler {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    public int position() { return bytes.size(); }
    public Assembler opcode(Opcode opcode) { bytes.write(opcode.code()); return this; }
    public Assembler register(int register) {
        if (register < 0 || register >= XCpu.REGISTER_COUNT) throw new IllegalArgumentException("bad register r" + register);
        bytes.write(register); return this;
    }
    public Assembler i32(int value) {
        for (int shift = 0; shift < 32; shift += 8) bytes.write(value >>> shift); return this;
    }
    public Assembler i64(long value) {
        for (int shift = 0; shift < 64; shift += 8) bytes.write((int) (value >>> shift)); return this;
    }
    public Assembler halt() { return opcode(Opcode.HALT); }
    public Assembler nop() { return opcode(Opcode.NOP); }
    public Assembler movi(int dst, long value) { return opcode(Opcode.MOVI).register(dst).i64(value); }
    public Assembler mov(int dst, int src) { return opcode(Opcode.MOV).register(dst).register(src); }
    public Assembler tri(Opcode opcode, int dst, int left, int right) {
        return opcode(opcode).register(dst).register(left).register(right);
    }
    public Assembler cmp(int left, int right) { return opcode(Opcode.CMP).register(left).register(right); }
    public Assembler jump(Opcode opcode, int address) { return opcode(opcode).i32(address); }
    public Assembler memory(Opcode opcode, int value, int address) { return opcode(opcode).register(value).register(address); }
    public Assembler push(int source) { return opcode(Opcode.PUSH).register(source); }
    public Assembler pop(int target) { return opcode(Opcode.POP).register(target); }
    public Assembler ret() { return opcode(Opcode.RET); }
    public Assembler syscall() { return opcode(Opcode.SYSCALL); }
    public Assembler raw(byte[] values) { bytes.writeBytes(values); return this; }
    public byte[] bytes() { return bytes.toByteArray(); }
}
