package com.xlang.compiler.sema;

import java.util.List;

/** Immutable, printable memory layout for a semantic type. */
public record TypeLayout(Type type, long size, int alignment, List<Member> members,
                         List<Padding> padding) {
    public TypeLayout {
        members = List.copyOf(members);
        padding = List.copyOf(padding);
    }
    public record Member(String name, Type type, long offset, long size, int alignment) {}
    public record Padding(long offset, long size) {}
}
