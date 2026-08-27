package com.xlang.vm;

import java.util.Arrays;

/** Mutable CPU state for the P4 XMachine. */
public final class XCpu {
    public static final int REGISTER_COUNT = 8;
    public static final int STACK_POINTER = 7;

    private final long[] registers = new long[REGISTER_COUNT];
    private int pc;
    private boolean zero;
    private boolean negative;
    private boolean halted;
    private long steps;

    public long register(int index) {
        checkRegister(index);
        return registers[index];
    }

    void register(int index, long value) {
        checkRegister(index);
        registers[index] = value;
    }

    public int pc() { return pc; }
    void pc(int value) { pc = value; }
    public boolean zero() { return zero; }
    public boolean negative() { return negative; }
    public boolean halted() { return halted; }
    public long steps() { return steps; }
    void halt() { halted = true; }
    void incrementSteps() { steps++; }

    void flags(long value) {
        zero = value == 0;
        negative = value < 0;
    }

    void comparison(long left, long right) {
        int comparison = Long.compare(left, right);
        zero = comparison == 0;
        negative = comparison < 0;
    }

    public Snapshot snapshot() {
        return new Snapshot(registers, pc, zero, negative, halted, steps);
    }

    private static void checkRegister(int index) {
        if (index < 0 || index >= REGISTER_COUNT) {
            throw new IllegalArgumentException("register index out of range: " + index);
        }
    }

    /** Immutable state suitable for traces and assertions. */
    public record Snapshot(long[] registers, int pc, boolean zero, boolean negative,
                           boolean halted, long steps) {
        public Snapshot { registers = Arrays.copyOf(registers, registers.length); }
        @Override public long[] registers() { return Arrays.copyOf(registers, registers.length); }
        public long register(int index) { return registers[index]; }
    }
}
