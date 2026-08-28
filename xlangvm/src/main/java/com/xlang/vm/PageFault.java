package com.xlang.vm;

/** Fault raised when virtual-address translation or protection checking fails. */
public final class PageFault extends MachineFault {
    private final int virtualAddress;
    private final Access access;

    public PageFault(int instructionAddress, int virtualAddress, Access access, String reason) {
        super(instructionAddress, "page fault on " + access.name().toLowerCase() + " at 0x"
            + String.format("%08x", virtualAddress) + ": " + reason);
        this.virtualAddress = virtualAddress;
        this.access = access;
    }

    public int virtualAddress() { return virtualAddress; }
    public Access access() { return access; }
}
