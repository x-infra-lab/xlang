package com.xlang.linker;

import com.xlang.compiler.object.XObject;
import com.xlang.vm.Opcode;
import com.xlang.vm.PageTable;
import com.xlang.vm.XOS;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Readable P7 linker: section layout, symbol resolution, and relocation patching. */
public final class Xld {
    public static final String DEFAULT_ENTRY = "main";
    private static final String MODULE_INITIALIZER = "$module_init";
    private static final int CALL_SIZE = 5;
    private static final int FINAL_CALL_AND_HALT_SIZE = 6;
    private static final int DATA_ALIGNMENT = 8;

    private Xld() {}

    public static LinkResult link(List<XObject> objects) throws LinkException {
        return link(objects, DEFAULT_ENTRY, false);
    }

    public static LinkResult link(List<XObject> objects, String entry, boolean verbose)
            throws LinkException {
        if (objects.isEmpty()) throw new LinkException("no input objects");
        if (entry == null || entry.isBlank()) throw new LinkException("entry symbol is empty");

        int initializerCount = countInitializers(objects);
        int startupSize;
        try {
            startupSize = Math.addExact(Math.multiplyExact(initializerCount, CALL_SIZE),
                FINAL_CALL_AND_HALT_SIZE);
        } catch (ArithmeticException exception) {
            throw new LinkException("too many module initializers");
        }
        List<ObjectLayout> layouts = layout(objects, startupSize);
        int textLength = layouts.get(layouts.size() - 1).textEnd();
        int dataLength = layouts.get(layouts.size() - 1).dataEnd();
        int dataAddress;
        try {
            dataAddress = PageTable.alignUp(textLength);
        } catch (IllegalArgumentException exception) {
            throw new LinkException("linked text is too large");
        }
        if ((long) dataAddress + dataLength > XOS.HEAP_BASE) {
            throw new LinkException("linked image overlaps heap base");
        }

        byte[] text = new byte[textLength];
        byte[] data = new byte[dataLength];
        for (int offset = 0; offset < startupSize - 1; offset += CALL_SIZE) {
            text[offset] = (byte) Opcode.CALL.code();
        }
        text[startupSize - 1] = (byte) Opcode.HALT.code();
        for (ObjectLayout layout : layouts) {
            byte[] objectText = layout.object().text();
            byte[] objectData = layout.object().data();
            System.arraycopy(objectText, 0, text, layout.textBase(), objectText.length);
            System.arraycopy(objectData, 0, data, layout.dataOffset(), objectData.length);
        }

        List<Map<String, ResolvedSymbol>> objectSymbols = new ArrayList<>();
        Map<String, ResolvedSymbol> globals = new LinkedHashMap<>();
        List<ResolvedSymbol> initializers = new ArrayList<>();
        for (ObjectLayout layout : layouts) {
            Map<String, ResolvedSymbol> local = new LinkedHashMap<>();
            for (XObject.Symbol symbol : layout.object().symbols()) {
                validateSymbol(layout, symbol);
                long address = symbol.section() == XObject.Section.TEXT
                    ? (long) layout.textBase() + symbol.offset()
                    : (long) dataAddress + layout.dataOffset() + symbol.offset();
                ResolvedSymbol resolved = new ResolvedSymbol(symbol.section(), address, layout.index());
                if (local.putIfAbsent(symbol.name(), resolved) != null) {
                    throw new LinkException(label(layout) + " defines symbol '" + symbol.name()
                        + "' more than once");
                }
                if (symbol.name().equals(MODULE_INITIALIZER)) initializers.add(resolved);
                if (symbol.global()) {
                    ResolvedSymbol previous = globals.putIfAbsent(symbol.name(), resolved);
                    if (previous != null) {
                        throw new LinkException("duplicate global symbol '" + symbol.name()
                            + "' in object[" + previous.objectIndex() + "] and " + label(layout));
                    }
                }
            }
            objectSymbols.add(local);
        }

        ResolvedSymbol entrySymbol = globals.get(entry);
        if (entrySymbol == null) throw new LinkException("undefined entry symbol '" + entry + "'");
        if (entrySymbol.section() != XObject.Section.TEXT) {
            throw new LinkException("entry symbol '" + entry + "' is not in text");
        }

        List<String> trace = new ArrayList<>();
        for (int index = 0; index < initializers.size(); index++) {
            int patchOffset = index * CALL_SIZE + 1;
            ResolvedSymbol initializer = initializers.get(index);
            patch(text, patchOffset, XObject.RelocationType.ABS32, initializer.address(), patchOffset,
                "startup -> object[" + initializer.objectIndex() + "] " + MODULE_INITIALIZER,
                verbose, trace);
        }
        int entryPatchOffset = initializers.size() * CALL_SIZE + 1;
        patch(text, entryPatchOffset, XObject.RelocationType.ABS32, entrySymbol.address(),
            entryPatchOffset,
            "startup -> " + entry, verbose, trace);
        for (ObjectLayout layout : layouts) {
            for (XObject.Relocation relocation : layout.object().relocations()) {
                int width = relocation.type() == XObject.RelocationType.ABS32 ? 4 : 8;
                int sectionLength = relocation.section() == XObject.Section.TEXT
                    ? layout.object().text().length : layout.object().data().length;
                if (relocation.offset() < 0 || relocation.offset() > sectionLength - width) {
                    throw new LinkException(label(layout) + " has out-of-range " + relocation.type()
                        + " relocation at " + relocation.section().name().toLowerCase()
                        + "+0x" + Integer.toHexString(relocation.offset()));
                }
                ResolvedSymbol symbol = objectSymbols.get(layout.index()).get(relocation.symbol());
                if (symbol == null) symbol = globals.get(relocation.symbol());
                if (symbol == null) {
                    throw new LinkException(label(layout) + " has undefined symbol '"
                        + relocation.symbol() + "'");
                }
                long value;
                try {
                    value = Math.addExact(symbol.address(), relocation.addend());
                } catch (ArithmeticException exception) {
                    throw new LinkException("relocation value overflow for symbol '"
                        + relocation.symbol() + "'");
                }
                byte[] target = relocation.section() == XObject.Section.TEXT ? text : data;
                int targetOffset = relocation.section() == XObject.Section.TEXT
                    ? layout.textBase() + relocation.offset()
                    : layout.dataOffset() + relocation.offset();
                int virtualAddress = relocation.section() == XObject.Section.TEXT
                    ? targetOffset : dataAddress + targetOffset;
                String description = label(layout) + " " + relocation.section().name().toLowerCase()
                    + "+0x" + Integer.toHexString(relocation.offset()) + " " + relocation.type()
                    + " " + relocation.symbol() + signedAddend(relocation.addend());
                patch(target, targetOffset, relocation.type(), value, virtualAddress,
                    description, verbose, trace);
            }
        }

        Map<String, Long> exported = new LinkedHashMap<>();
        globals.forEach((name, symbol) -> exported.put(name, symbol.address()));
        return new LinkResult(new XExecutable(0, dataAddress, text, data, exported), trace);
    }

    private static int countInitializers(List<XObject> objects) throws LinkException {
        int count = 0;
        for (int index = 0; index < objects.size(); index++) {
            XObject object = objects.get(index);
            if (object == null) throw new LinkException("object[" + index + "] is null");
            for (XObject.Symbol symbol : object.symbols()) {
                if (MODULE_INITIALIZER.equals(symbol.name())) {
                    if (symbol.section() != XObject.Section.TEXT) {
                        throw new LinkException("object[" + index + "] module initializer is not in text");
                    }
                    count++;
                }
            }
        }
        return count;
    }

    private static List<ObjectLayout> layout(List<XObject> objects, int startupSize)
            throws LinkException {
        List<ObjectLayout> result = new ArrayList<>();
        int textCursor = startupSize;
        int dataCursor = 0;
        for (int index = 0; index < objects.size(); index++) {
            XObject object = objects.get(index);
            if (object == null) throw new LinkException("object[" + index + "] is null");
            dataCursor = align(dataCursor, DATA_ALIGNMENT);
            int textEnd;
            int dataEnd;
            try {
                textEnd = Math.addExact(textCursor, object.text().length);
                dataEnd = Math.addExact(dataCursor, object.data().length);
            } catch (ArithmeticException exception) {
                throw new LinkException("input sections are too large");
            }
            result.add(new ObjectLayout(index, object, textCursor, textEnd, dataCursor, dataEnd));
            textCursor = textEnd;
            dataCursor = dataEnd;
        }
        return result;
    }

    private static void validateSymbol(ObjectLayout layout, XObject.Symbol symbol)
            throws LinkException {
        int sectionLength = symbol.section() == XObject.Section.TEXT
            ? layout.object().text().length : layout.object().data().length;
        if (symbol.name() == null || symbol.name().isEmpty()) {
            throw new LinkException(label(layout) + " contains an empty symbol name");
        }
        if (symbol.offset() < 0 || symbol.size() < 0
                || (long) symbol.offset() + symbol.size() > sectionLength) {
            throw new LinkException(label(layout) + " has out-of-range symbol '"
                + symbol.name() + "'");
        }
    }

    private static void patch(byte[] target, int offset, XObject.RelocationType type,
                              long value, int virtualAddress, String description,
                              boolean verbose, List<String> trace) throws LinkException {
        if (value < 0 || (type == XObject.RelocationType.ABS32 && value > Integer.MAX_VALUE)) {
            throw new LinkException(type + " relocation value out of range for " + description
                + ": " + value);
        }
        int width = type == XObject.RelocationType.ABS32 ? 4 : 8;
        byte[] before = Arrays.copyOfRange(target, offset, offset + width);
        for (int byteIndex = 0; byteIndex < width; byteIndex++) {
            target[offset + byteIndex] = (byte) (value >>> (byteIndex * 8));
        }
        if (verbose) {
            trace.add(String.format("relocate %-48s -> 0x%x", description, value));
            for (int byteIndex = 0; byteIndex < width; byteIndex++) {
                trace.add(String.format("  byte [0x%08x] %02x -> %02x", virtualAddress + byteIndex,
                    before[byteIndex] & 0xff, target[offset + byteIndex] & 0xff));
            }
        }
    }

    private static int align(int value, int alignment) throws LinkException {
        long aligned = ((long) value + alignment - 1) / alignment * alignment;
        if (aligned > Integer.MAX_VALUE) throw new LinkException("section layout overflow");
        return (int) aligned;
    }

    private static String signedAddend(long addend) {
        if (addend == 0) return "";
        return addend > 0 ? "+" + addend : Long.toString(addend);
    }

    private static String label(ObjectLayout layout) { return "object[" + layout.index() + "]"; }

    public record LinkResult(XExecutable executable, List<String> trace) {
        public LinkResult { trace = List.copyOf(trace); }
    }

    private record ObjectLayout(int index, XObject object, int textBase, int textEnd,
                                int dataOffset, int dataEnd) {}
    private record ResolvedSymbol(XObject.Section section, long address, int objectIndex) {}
}
