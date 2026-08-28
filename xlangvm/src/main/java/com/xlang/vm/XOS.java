package com.xlang.vm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** P5 teaching kernel: page tables, mappings, heap break, and write syscall. */
public final class XOS {
    public static final int HEAP_BASE = 0x0001_0000;
    public static final int MMAP_BASE = 0x0004_0000;
    public static final int STACK_TOP = 0x0010_0000;
    public static final int STACK_BYTES = PageTable.PAGE_SIZE * 4;
    public static final long SYS_WRITE = 1;

    private final byte[] physicalMemory;
    private final PageTable pageTable;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int programEnd;
    private int programMappedEnd;
    private int currentBreak = HEAP_BASE;
    private int nextMmap = MMAP_BASE;

    public XOS(byte[] physicalMemory) {
        this.physicalMemory = physicalMemory;
        pageTable = new PageTable(physicalMemory.length);
    }

    public void boot(byte[] program) {
        if (program.length == 0) throw new IllegalArgumentException("program is empty");
        if (program.length > HEAP_BASE) throw new IllegalArgumentException("program overlaps heap base");
        java.util.Arrays.fill(physicalMemory, (byte) 0);
        pageTable.reset();
        output.reset();
        programEnd = program.length;
        programMappedEnd = PageTable.alignUp(program.length);
        pageTable.map(0, programMappedEnd, EnumSet.of(Protection.READ, Protection.EXECUTE), "code");
        pageTable.map(STACK_TOP - STACK_BYTES, STACK_BYTES,
            EnumSet.of(Protection.READ, Protection.WRITE), "stack");
        for (int index = 0; index < program.length; index++) {
            physicalMemory[pageTable.translateKernel(index)] = program[index];
        }
        currentBreak = HEAP_BASE;
        nextMmap = MMAP_BASE;
    }

    public PageTable pageTable() { return pageTable; }
    public int programEnd() { return programEnd; }
    public int currentBreak() { return currentBreak; }

    /** Queries with zero, otherwise grows or shrinks the process heap. */
    public int brk(int requested) {
        if (requested == 0) return currentBreak;
        if (requested < HEAP_BASE || requested >= MMAP_BASE) {
            throw new IllegalArgumentException("brk outside heap range: 0x" + String.format("%08x", requested));
        }
        int oldMappedEnd = PageTable.alignUp(currentBreak);
        int newMappedEnd = PageTable.alignUp(requested);
        if (newMappedEnd > oldMappedEnd) {
            pageTable.map(oldMappedEnd, newMappedEnd - oldMappedEnd,
                EnumSet.of(Protection.READ, Protection.WRITE), "heap");
        } else if (newMappedEnd < oldMappedEnd) {
            pageTable.unmap(newMappedEnd, oldMappedEnd - newMappedEnd);
        }
        currentBreak = requested;
        return currentBreak;
    }

    public int mmap(int length, EnumSet<Protection> protections, String name) {
        if (length <= 0) throw new IllegalArgumentException("mmap length must be positive");
        int mappedLength = PageTable.alignUp(length);
        if (nextMmap > STACK_TOP - STACK_BYTES - mappedLength) {
            throw new IllegalStateException("virtual mmap space exhausted");
        }
        int address = nextMmap;
        pageTable.map(address, mappedLength, protections, name == null ? "mmap" : name);
        nextMmap += mappedLength;
        return address;
    }

    public void mprotect(int address, int length, EnumSet<Protection> protections) {
        pageTable.protect(address, length, protections);
    }

    public int translate(int virtualAddress, Access access, int instructionAddress) {
        return pageTable.translate(virtualAddress, access, instructionAddress);
    }

    public int readByte(int virtualAddress, Access access, int instructionAddress) {
        return physicalMemory[translate(virtualAddress, access, instructionAddress)] & 0xff;
    }

    public long readLong(int virtualAddress, int instructionAddress) {
        long value = 0;
        for (int offset = 0; offset < 8; offset++) {
            value |= (long) readByte(virtualAddress + offset, Access.READ, instructionAddress) << (offset * 8);
        }
        return value;
    }

    public byte[] readBytes(int virtualAddress, int length, int instructionAddress) {
        if (length < 0) throw new IllegalArgumentException("length must be non-negative");
        byte[] bytes = new byte[length];
        for (int offset = 0; offset < length; offset++) {
            bytes[offset] = (byte) readByte(virtualAddress + offset, Access.READ, instructionAddress);
        }
        return bytes;
    }

    public void writeLong(int virtualAddress, long value, int instructionAddress) {
        for (int offset = 0; offset < 8; offset++) {
            int physical = translate(virtualAddress + offset, Access.WRITE, instructionAddress);
            physicalMemory[physical] = (byte) (value >>> (offset * 8));
        }
    }

    /** Kernel-only helper for fixtures/loaders; bypasses user protection, not mapping. */
    public void writeKernel(int virtualAddress, byte[] bytes) {
        for (int offset = 0; offset < bytes.length; offset++) {
            physicalMemory[pageTable.translateKernel(virtualAddress + offset)] = bytes[offset];
        }
    }

    public long syscall(long number, long fd, long buffer, long length, int instructionAddress) {
        if (number != SYS_WRITE) throw new MachineFault(instructionAddress, "unknown syscall " + number);
        if (fd != 1 && fd != 2) throw new MachineFault(instructionAddress, "write only supports fd 1 or 2, got " + fd);
        if (length < 0 || length > Integer.MAX_VALUE) throw new MachineFault(instructionAddress, "invalid write length " + length);
        int address = checkedVirtualAddress(buffer, instructionAddress);
        byte[] bytes = readBytes(address, (int) length, instructionAddress);
        output.writeBytes(bytes);
        return bytes.length;
    }

    public byte[] outputBytes() { return output.toByteArray(); }
    public String outputText() { return output.toString(StandardCharsets.UTF_8); }

    public List<MemoryRegion> regions() {
        List<MemoryRegion> regions = new ArrayList<>();
        for (PageTable.Mapping mapping : pageTable.mappings()) {
            if (!regions.isEmpty()) {
                MemoryRegion previous = regions.get(regions.size() - 1);
                if (previous.end() == mapping.virtualStart()
                        && previous.name().equals(mapping.name())
                        && previous.protections().equals(mapping.protections())
                        && previous.lastPhysicalFrame() + 1 == mapping.physicalFrame()) {
                    regions.set(regions.size() - 1, previous.extend(mapping));
                    continue;
                }
            }
            regions.add(MemoryRegion.from(mapping));
        }
        return List.copyOf(regions);
    }

    public String describeMemory() {
        StringBuilder result = new StringBuilder();
        result.append("virtual range         prot  physical frames  name\n");
        for (MemoryRegion region : regions()) {
            result.append(String.format("%08x-%08x  %-3s   %4d..%-4d       %s%n",
                region.start(), region.end() - 1, Protection.format(region.protections()),
                region.firstPhysicalFrame(), region.lastPhysicalFrame(), region.name()));
        }
        result.append(String.format("brk = %08x%n", currentBreak));
        result.append("physical pages: " + pageTable.usedFrames() + " used, " + pageTable.freeFrames() + " free\n");
        return result.toString();
    }

    private static int checkedVirtualAddress(long address, int instructionAddress) {
        if (address < 0 || address > Integer.MAX_VALUE) {
            throw new PageFault(instructionAddress, (int) address, Access.READ, "address is outside virtual space");
        }
        return (int) address;
    }

    public record MemoryRegion(int start, int end, EnumSet<Protection> protections, String name,
                               int firstPhysicalFrame, int lastPhysicalFrame) {
        public MemoryRegion { protections = EnumSet.copyOf(protections); }
        @Override public EnumSet<Protection> protections() { return EnumSet.copyOf(protections); }
        static MemoryRegion from(PageTable.Mapping mapping) {
            return new MemoryRegion(mapping.virtualStart(), mapping.virtualEnd(), mapping.protections(),
                mapping.name(), mapping.physicalFrame(), mapping.physicalFrame());
        }
        MemoryRegion extend(PageTable.Mapping mapping) {
            return new MemoryRegion(start, mapping.virtualEnd(), protections, name,
                firstPhysicalFrame, mapping.physicalFrame());
        }
    }
}
