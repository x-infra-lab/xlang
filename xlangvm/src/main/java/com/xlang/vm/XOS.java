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
    public static final int STACK_BYTES = PageTable.PAGE_SIZE * 8;
    public static final long SYS_WRITE = 1;
    public static final long SYS_EXIT = 2;
    public static final long SYS_BRK = 3;

    private final byte[] physicalMemory;
    private final PageTable pageTable;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final List<SyscallEvent> syscallEvents = new ArrayList<>();
    private int programEnd;
    private int currentBreak = HEAP_BASE;
    private int nextMmap = MMAP_BASE;
    private boolean exited;
    private long exitCode;

    public XOS(byte[] physicalMemory) {
        this.physicalMemory = physicalMemory;
        pageTable = new PageTable(physicalMemory.length);
    }

    public void boot(byte[] program) {
        if (program.length == 0) throw new IllegalArgumentException("program is empty");
        if (program.length > HEAP_BASE) throw new IllegalArgumentException("program overlaps heap base");
        resetProcess();
        programEnd = program.length;
        int mappedEnd = PageTable.alignUp(program.length);
        pageTable.map(0, mappedEnd, EnumSet.of(Protection.READ, Protection.EXECUTE), "code");
        mapStack();
        writeKernel(0, program);
    }

    /** Boots a linked image with separately protected text and data segments. */
    public void boot(byte[] text, int dataAddress, byte[] data) {
        if (text.length == 0) throw new IllegalArgumentException("program text is empty");
        if (dataAddress < text.length || dataAddress % PageTable.PAGE_SIZE != 0) {
            throw new IllegalArgumentException("data address must be page aligned after text: " + dataAddress);
        }
        if ((long) dataAddress + data.length > HEAP_BASE) {
            throw new IllegalArgumentException("program overlaps heap base");
        }
        resetProcess();
        programEnd = data.length == 0 ? text.length : dataAddress + data.length;
        pageTable.map(0, PageTable.alignUp(text.length),
            EnumSet.of(Protection.READ, Protection.EXECUTE), "text");
        if (data.length > 0) {
            pageTable.map(dataAddress, PageTable.alignUp(data.length),
                EnumSet.of(Protection.READ, Protection.WRITE), "data");
        }
        mapStack();
        writeKernel(0, text);
        if (data.length > 0) writeKernel(dataAddress, data);
    }

    private void resetProcess() {
        java.util.Arrays.fill(physicalMemory, (byte) 0);
        pageTable.reset();
        output.reset();
        syscallEvents.clear();
        exited = false;
        exitCode = 0;
        currentBreak = HEAP_BASE;
        nextMmap = MMAP_BASE;
    }

    private void mapStack() {
        pageTable.map(STACK_TOP - STACK_BYTES, STACK_BYTES,
            EnumSet.of(Protection.READ, Protection.WRITE), "stack");
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

    public void writeByte(int virtualAddress, long value, int instructionAddress) {
        int physical = translate(virtualAddress, Access.WRITE, instructionAddress);
        physicalMemory[physical] = (byte) value;
    }

    /** Kernel-only helper for fixtures/loaders; bypasses user protection, not mapping. */
    public void writeKernel(int virtualAddress, byte[] bytes) {
        for (int offset = 0; offset < bytes.length; offset++) {
            physicalMemory[pageTable.translateKernel(virtualAddress + offset)] = bytes[offset];
        }
    }

    public long syscall(long number, long fd, long buffer, long length, int instructionAddress) {
        if (number == SYS_WRITE) {
            if (fd != 1 && fd != 2) throw new MachineFault(instructionAddress, "write only supports fd 1 or 2, got " + fd);
            if (length < 0 || length > Integer.MAX_VALUE) throw new MachineFault(instructionAddress, "invalid write length " + length);
            int address = checkedVirtualAddress(buffer, instructionAddress);
            byte[] bytes = readBytes(address, (int) length, instructionAddress);
            output.writeBytes(bytes);
            long result = bytes.length;
            syscallEvents.add(new SyscallEvent(number, "write", List.of(fd, buffer, length), result,
                escape(bytes)));
            return result;
        }
        if (number == SYS_EXIT) {
            exited = true;
            exitCode = fd;
            syscallEvents.add(new SyscallEvent(number, "exit", List.of(fd), 0, ""));
            return 0;
        }
        if (number == SYS_BRK) {
            long result = -1;
            if (fd >= Integer.MIN_VALUE && fd <= Integer.MAX_VALUE) {
                try {
                    result = brk((int) fd);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // A kernel-style failure is observable as -1 rather than a host exception.
                }
            }
            syscallEvents.add(new SyscallEvent(number, "brk", List.of(fd), result, ""));
            return result;
        }
        throw new MachineFault(instructionAddress, "unknown syscall " + number);
    }

    public byte[] outputBytes() { return output.toByteArray(); }
    public String outputText() { return output.toString(StandardCharsets.UTF_8); }
    public List<SyscallEvent> syscallEvents() { return List.copyOf(syscallEvents); }
    public boolean exited() { return exited; }
    public long exitCode() { return exitCode; }

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

    private static String escape(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            switch (unsigned) {
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                default -> {
                    if (unsigned >= 0x20 && unsigned <= 0x7e) result.append((char) unsigned);
                    else result.append(String.format("\\x%02x", unsigned));
                }
            }
        }
        return result.toString();
    }

    public record SyscallEvent(long number, String name, List<Long> arguments,
                               long result, String detail) {
        public SyscallEvent { arguments = List.copyOf(arguments); }

        public String format() {
            return switch (name) {
                case "write" -> String.format("write(fd=%d, buffer=0x%08x, length=%d) = %d \"%s\"",
                    arguments.get(0), arguments.get(1), arguments.get(2), result, detail);
                case "exit" -> String.format("exit(status=%d) = %d", arguments.get(0), result);
                case "brk" -> String.format("brk(address=0x%08x) = %s", arguments.get(0),
                    result < 0 ? "-1" : String.format("0x%08x", result));
                default -> name + arguments + " = " + result;
            };
        }
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
