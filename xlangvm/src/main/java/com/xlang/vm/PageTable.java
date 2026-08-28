package com.xlang.vm;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Single-level teaching page table backed by a physical-frame allocator. */
public final class PageTable {
    public static final int PAGE_SIZE = 256;

    private final int frameCount;
    private final boolean[] allocatedFrames;
    private final Map<Integer, MutableMapping> mappings = new TreeMap<>();

    public PageTable(int physicalBytes) {
        frameCount = physicalBytes / PAGE_SIZE;
        if (frameCount == 0) throw new IllegalArgumentException("physical memory must contain at least one page");
        allocatedFrames = new boolean[frameCount];
    }

    public void reset() {
        mappings.clear();
        java.util.Arrays.fill(allocatedFrames, false);
    }

    public void map(int start, int length, EnumSet<Protection> protections, String name) {
        requirePageAligned(start, "mapping start");
        if (length <= 0) throw new IllegalArgumentException("mapping length must be positive");
        int pages = pagesFor(length);
        for (int page = 0; page < pages; page++) {
            int virtualPage = start / PAGE_SIZE + page;
            if (mappings.containsKey(virtualPage)) throw new IllegalArgumentException("virtual page already mapped: " + virtualPage);
        }
        if (freeFrames() < pages) throw new IllegalStateException("out of physical frames: need " + pages + ", free " + freeFrames());
        for (int page = 0; page < pages; page++) {
            int virtualPage = start / PAGE_SIZE + page;
            int frame = allocateFrame();
            mappings.put(virtualPage, new MutableMapping(virtualPage, frame, EnumSet.copyOf(protections), name));
        }
    }

    public void unmap(int start, int length) {
        requirePageAligned(start, "unmap start");
        if (length <= 0) return;
        int pages = pagesFor(length);
        for (int page = 0; page < pages; page++) {
            MutableMapping removed = mappings.remove(start / PAGE_SIZE + page);
            if (removed != null) allocatedFrames[removed.physicalFrame] = false;
        }
    }

    public void protect(int start, int length, EnumSet<Protection> protections) {
        requirePageAligned(start, "protect start");
        if (length <= 0) throw new IllegalArgumentException("protect length must be positive");
        int pages = pagesFor(length);
        for (int page = 0; page < pages; page++) {
            MutableMapping mapping = mappings.get(start / PAGE_SIZE + page);
            if (mapping == null) throw new IllegalArgumentException("cannot protect unmapped virtual page");
            mapping.protections.clear();
            mapping.protections.addAll(protections);
        }
    }

    public int translate(int virtualAddress, Access access, int instructionAddress) {
        if (virtualAddress < 0) throw new PageFault(instructionAddress, virtualAddress, access, "address is outside virtual space");
        int virtualPage = virtualAddress / PAGE_SIZE;
        MutableMapping mapping = mappings.get(virtualPage);
        if (mapping == null) throw new PageFault(instructionAddress, virtualAddress, access, "page is not mapped");
        if (!Protection.allows(mapping.protections, access)) {
            throw new PageFault(instructionAddress, virtualAddress, access,
                "mapping '" + mapping.name + "' has protection " + Protection.format(mapping.protections));
        }
        return mapping.physicalFrame * PAGE_SIZE + virtualAddress % PAGE_SIZE;
    }

    int translateKernel(int virtualAddress) {
        MutableMapping mapping = mappings.get(virtualAddress / PAGE_SIZE);
        if (mapping == null) throw new IllegalArgumentException("kernel access to unmapped address " + virtualAddress);
        return mapping.physicalFrame * PAGE_SIZE + virtualAddress % PAGE_SIZE;
    }

    public List<Mapping> mappings() {
        List<Mapping> result = new ArrayList<>();
        for (MutableMapping mapping : mappings.values()) {
            result.add(new Mapping(mapping.virtualPage * PAGE_SIZE, mapping.physicalFrame,
                mapping.protections, mapping.name));
        }
        return List.copyOf(result);
    }

    public int usedFrames() { return frameCount - freeFrames(); }
    public int freeFrames() {
        int free = 0;
        for (boolean allocated : allocatedFrames) if (!allocated) free++;
        return free;
    }

    public static int alignDown(int value) { return value / PAGE_SIZE * PAGE_SIZE; }
    public static int alignUp(int value) {
        if (value < 0 || value > Integer.MAX_VALUE - (PAGE_SIZE - 1)) throw new IllegalArgumentException("address cannot be aligned: " + value);
        return (value + PAGE_SIZE - 1) / PAGE_SIZE * PAGE_SIZE;
    }
    public static int pagesFor(int bytes) { return (bytes + PAGE_SIZE - 1) / PAGE_SIZE; }

    private int allocateFrame() {
        for (int frame = 0; frame < allocatedFrames.length; frame++) {
            if (!allocatedFrames[frame]) { allocatedFrames[frame] = true; return frame; }
        }
        throw new IllegalStateException("out of physical frames");
    }

    private static void requirePageAligned(int address, String label) {
        if (address < 0 || address % PAGE_SIZE != 0) throw new IllegalArgumentException(label + " must be page aligned: " + address);
    }

    private static final class MutableMapping {
        private final int virtualPage;
        private final int physicalFrame;
        private final EnumSet<Protection> protections;
        private final String name;
        MutableMapping(int virtualPage, int physicalFrame, EnumSet<Protection> protections, String name) {
            this.virtualPage = virtualPage; this.physicalFrame = physicalFrame;
            this.protections = protections; this.name = name;
        }
    }

    public record Mapping(int virtualStart, int physicalFrame,
                          EnumSet<Protection> protections, String name) {
        public Mapping { protections = EnumSet.copyOf(protections); }
        @Override public EnumSet<Protection> protections() { return EnumSet.copyOf(protections); }
        public int virtualEnd() { return virtualStart + PAGE_SIZE; }
        public int physicalStart() { return physicalFrame * PAGE_SIZE; }
    }
}
