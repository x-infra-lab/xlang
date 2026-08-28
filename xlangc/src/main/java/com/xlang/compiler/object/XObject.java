package com.xlang.compiler.object;

import java.util.Arrays;
import java.util.List;

/** Relocatable xlang object emitted by the P6 backend and consumed by P7. */
public record XObject(byte[] text, byte[] data, List<Symbol> symbols,
                      List<Relocation> relocations) {
    public XObject {
        text = Arrays.copyOf(text, text.length);
        data = Arrays.copyOf(data, data.length);
        symbols = List.copyOf(symbols);
        relocations = List.copyOf(relocations);
    }
    @Override public byte[] text() { return Arrays.copyOf(text, text.length); }
    @Override public byte[] data() { return Arrays.copyOf(data, data.length); }

    public enum Section { TEXT, DATA }
    public enum RelocationType { ABS32, ABS64 }

    public record Symbol(String name, Section section, int offset, int size, boolean global) {}
    public record Relocation(Section section, int offset, RelocationType type,
                             String symbol, long addend) {}
}
