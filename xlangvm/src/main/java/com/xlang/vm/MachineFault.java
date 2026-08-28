package com.xlang.vm;

/** Deterministic VM trap with the instruction address that caused it. */
public class MachineFault extends RuntimeException {
    private final int address;

    public MachineFault(int address, String message) {
        super("fault at 0x" + String.format("%04x", address) + ": " + message);
        this.address = address;
    }

    public int address() { return address; }
}
