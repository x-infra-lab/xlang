package com.xlang.vm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A byte-addressed machine with a small decoded P4 instruction set. */
public final class XMachine {
    public static final int DEFAULT_RAM_SIZE = 64 * 1024;
    public static final long DEFAULT_STEP_LIMIT = 1_000_000;

    private final byte[] ram;
    private final XOS os;
    private XCpu cpu = new XCpu();
    private int programEnd;

    public XMachine() { this(DEFAULT_RAM_SIZE); }

    public XMachine(int ramSize) {
        if (ramSize < 16) throw new IllegalArgumentException("RAM must be at least 16 bytes");
        ram = new byte[ramSize];
        os = new XOS(ram);
    }

    public XCpu cpu() { return cpu; }
    public XOS os() { return os; }
    public int ramSize() { return ram.length; }

    public void load(byte[] program) {
        if (program.length == 0) throw new IllegalArgumentException("program is empty");
        os.boot(program);
        programEnd = program.length;
        resetCpu();
    }

    /** Loads a linked image, preserving separate text and data protections. */
    public void load(byte[] text, int dataAddress, byte[] data) {
        os.boot(text, dataAddress, data);
        programEnd = text.length;
        resetCpu();
    }

    private void resetCpu() {
        cpu = new XCpu();
        cpu.register(XCpu.STACK_POINTER, XOS.STACK_TOP);
    }

    public ExecutionResult run() { return run(DEFAULT_STEP_LIMIT, false); }

    public ExecutionResult run(long maxSteps, boolean trace) {
        if (programEnd == 0) throw new IllegalStateException("no program loaded");
        if (maxSteps < 1) throw new IllegalArgumentException("step limit must be positive");
        List<TraceEntry> entries = new ArrayList<>();
        while (!cpu.halted()) {
            if (cpu.steps() >= maxSteps) throw fault(cpu.pc(), "instruction step limit exceeded (" + maxSteps + ")");
            TraceEntry entry = step();
            if (trace) entries.add(entry);
        }
        return new ExecutionResult(cpu.snapshot(), entries, os.outputText());
    }

    public TraceEntry step() {
        if (programEnd == 0) throw new IllegalStateException("no program loaded");
        if (cpu.halted()) throw new IllegalStateException("CPU is halted");
        int address = cpu.pc();
        int opcodeByte = instructionByte(address, address);
        Opcode opcode = Opcode.fromByte(opcodeByte);
        if (opcode == null) throw fault(address, "unknown opcode 0x" + String.format("%02x", opcodeByte));
        requireInstructionRange(address, opcode.size(), address);
        byte[] encoded = new byte[opcode.size()];
        for (int offset = 0; offset < encoded.length; offset++) {
            encoded[offset] = (byte) os.readByte(address + offset, Access.EXECUTE, address);
        }
        int next = address + opcode.size();
        cpu.pc(next);
        String decoded = opcode.mnemonic();

        switch (opcode) {
            case HALT -> { cpu.halt(); decoded = "halt"; }
            case NOP -> decoded = "nop";
            case MOVI -> {
                int dst = registerOperand(address + 1, address);
                long value = readI64(address + 2);
                cpu.register(dst, value); cpu.flags(value);
                decoded = "movi r" + dst + ", " + value;
            }
            case MOV -> {
                int dst = registerOperand(address + 1, address), src = registerOperand(address + 2, address);
                long value = cpu.register(src); cpu.register(dst, value); cpu.flags(value);
                decoded = "mov r" + dst + ", r" + src;
            }
            case ADD, SUB, MUL, DIV, MOD -> decoded = arithmetic(opcode, address);
            case CMP -> {
                int left = registerOperand(address + 1, address), right = registerOperand(address + 2, address);
                cpu.comparison(cpu.register(left), cpu.register(right));
                decoded = "cmp r" + left + ", r" + right;
            }
            case JMP, JZ, JNZ, JN -> {
                int target = readI32(address + 1);
                boolean taken = switch (opcode) {
                    case JMP -> true; case JZ -> cpu.zero(); case JNZ -> !cpu.zero(); case JN -> cpu.negative();
                    default -> false;
                };
                if (taken) jump(target, address);
                decoded = opcode.mnemonic() + " 0x" + String.format("%04x", target) + (taken ? " [taken]" : "");
            }
            case LOAD64 -> {
                int dst = registerOperand(address + 1, address), addressRegister = registerOperand(address + 2, address);
                int memoryAddress = memoryAddress(cpu.register(addressRegister), address);
                long value = readMemoryI64(memoryAddress, address);
                cpu.register(dst, value); cpu.flags(value);
                decoded = "load64 r" + dst + ", [r" + addressRegister + "]";
            }
            case STORE64 -> {
                int src = registerOperand(address + 1, address), addressRegister = registerOperand(address + 2, address);
                int memoryAddress = memoryAddress(cpu.register(addressRegister), address);
                writeMemoryI64(memoryAddress, cpu.register(src), address);
                decoded = "store64 r" + src + ", [r" + addressRegister + "]";
            }
            case LOAD8 -> {
                int dst = registerOperand(address + 1, address), addressRegister = registerOperand(address + 2, address);
                int memoryAddress = memoryAddress(cpu.register(addressRegister), address);
                long value = os.readByte(memoryAddress, Access.READ, address);
                cpu.register(dst, value); cpu.flags(value);
                decoded = "load8 r" + dst + ", [r" + addressRegister + "]";
            }
            case STORE8 -> {
                int src = registerOperand(address + 1, address), addressRegister = registerOperand(address + 2, address);
                int memoryAddress = memoryAddress(cpu.register(addressRegister), address);
                os.writeByte(memoryAddress, cpu.register(src), address);
                decoded = "store8 r" + src + ", [r" + addressRegister + "]";
            }
            case PUSH -> {
                int src = registerOperand(address + 1, address);
                push(cpu.register(src), address);
                decoded = "push r" + src;
            }
            case POP -> {
                int dst = registerOperand(address + 1, address);
                long value = pop(address); cpu.register(dst, value); cpu.flags(value);
                decoded = "pop r" + dst;
            }
            case CALL -> {
                int target = readI32(address + 1);
                push(next, address); jump(target, address);
                decoded = "call 0x" + String.format("%04x", target);
            }
            case RET -> {
                int target = checkedAddress(pop(address), address, "return address");
                jump(target, address); decoded = "ret";
            }
            case SYSCALL -> {
                long result = os.syscall(cpu.register(0), cpu.register(1), cpu.register(2), cpu.register(3), address);
                cpu.register(0, result); cpu.flags(result); decoded = "syscall";
                if (os.exited()) cpu.halt();
            }
        }
        cpu.incrementSteps();
        return new TraceEntry(address, encoded, decoded, cpu.snapshot());
    }

    public long readLong(int address) { return os.readLong(address, cpu.pc()); }

    private String arithmetic(Opcode opcode, int address) {
        int dst = registerOperand(address + 1, address);
        int left = registerOperand(address + 2, address);
        int right = registerOperand(address + 3, address);
        long a = cpu.register(left), b = cpu.register(right);
        long result = switch (opcode) {
            case ADD -> a + b; case SUB -> a - b; case MUL -> a * b;
            case DIV -> { if (b == 0) throw fault(address, "division by zero"); yield a / b; }
            case MOD -> { if (b == 0) throw fault(address, "modulo by zero"); yield a % b; }
            default -> throw new IllegalStateException("not arithmetic: " + opcode);
        };
        cpu.register(dst, result); cpu.flags(result);
        return opcode.mnemonic() + " r" + dst + ", r" + left + ", r" + right;
    }

    private int instructionByte(int at, int faultAddress) {
        if (at < 0 || at >= programEnd) throw fault(faultAddress, "instruction fetch outside loaded program: " + at);
        return os.readByte(at, Access.EXECUTE, faultAddress);
    }

    private void requireInstructionRange(int start, int length, int faultAddress) {
        if (start < 0 || length < 0 || start > programEnd - length) {
            throw fault(faultAddress, "truncated instruction");
        }
    }

    private int registerOperand(int at, int faultAddress) {
        int register = instructionByte(at, faultAddress);
        if (register >= XCpu.REGISTER_COUNT) throw fault(faultAddress, "invalid register r" + register);
        return register;
    }

    private int readI32(int at) {
        return instructionByte(at, at) | instructionByte(at + 1, at) << 8
            | instructionByte(at + 2, at) << 16 | instructionByte(at + 3, at) << 24;
    }

    private long readI64(int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) value |= (long) instructionByte(at + i, at) << (i * 8);
        return value;
    }

    private long readMemoryI64(int at, int faultAddress) {
        return os.readLong(at, faultAddress);
    }

    private void writeMemoryI64(int at, long value, int faultAddress) {
        os.writeLong(at, value, faultAddress);
    }

    private int memoryAddress(long value, int faultAddress) {
        return checkedAddress(value, faultAddress, "memory address");
    }

    private int checkedAddress(long value, int faultAddress, String kind) {
        if (value < 0 || value > Integer.MAX_VALUE) throw fault(faultAddress, kind + " out of range: " + value);
        return (int) value;
    }

    private void jump(int target, int faultAddress) {
        if (target < 0 || target >= programEnd) throw fault(faultAddress, "jump target outside loaded program: " + target);
        cpu.pc(target);
    }

    private void push(long value, int faultAddress) {
        long next = cpu.register(XCpu.STACK_POINTER) - 8;
        int address = checkedAddress(next, faultAddress, "stack pointer");
        writeMemoryI64(address, value, faultAddress);
        cpu.register(XCpu.STACK_POINTER, address);
    }

    private long pop(int faultAddress) {
        int address = checkedAddress(cpu.register(XCpu.STACK_POINTER), faultAddress, "stack pointer");
        if (address > XOS.STACK_TOP - 8) throw fault(faultAddress, "stack underflow");
        long value = readMemoryI64(address, faultAddress);
        cpu.register(XCpu.STACK_POINTER, address + 8L);
        return value;
    }

    private static MachineFault fault(int address, String message) { return new MachineFault(address, message); }

    public record TraceEntry(int address, byte[] encoded, String instruction, XCpu.Snapshot after) {
        public TraceEntry { encoded = Arrays.copyOf(encoded, encoded.length); }
        @Override public byte[] encoded() { return Arrays.copyOf(encoded, encoded.length); }
        public String format() {
            return String.format("%04x: %-29s %-28s ; pc=%04x r0=%d Z=%d N=%d",
                address, HexProgram.format(encoded), instruction, after.pc(), after.register(0),
                after.zero() ? 1 : 0, after.negative() ? 1 : 0);
        }
    }

    public record ExecutionResult(XCpu.Snapshot cpu, List<TraceEntry> trace, String output) {
        public ExecutionResult { trace = List.copyOf(trace); }
    }
}
