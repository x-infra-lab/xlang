package com.xlang.compiler.sema;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** P9 ABI layout: int/string/pointers are 8 bytes, bool is 1 byte. */
public final class LayoutEngine {
    private final Map<Type, TypeLayout> cache = new IdentityHashMap<>();
    private final Map<Type, Boolean> active = new IdentityHashMap<>();

    public TypeLayout layout(Type type) {
        TypeLayout known = cache.get(type);
        if (known != null) return known;
        if (active.put(type, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("recursive by-value type '" + type.displayName() + "'");
        }
        try {
            TypeLayout result = compute(type);
            cache.put(type, result);
            return result;
        } finally {
            active.remove(type);
        }
    }

    private TypeLayout compute(Type type) {
        if (type == Type.INT || type == Type.STRING || type instanceof Type.Pointer) {
            return scalar(type, 8, 8);
        }
        if (type == Type.BOOL) return scalar(type, 1, 1);
        if (type instanceof Type.Array array) {
            TypeLayout element = layout(array.element());
            long size = multiply(array.length(), element.size(), type);
            return new TypeLayout(type, size, element.alignment(), List.of(), List.of());
        }
        if (type instanceof Type.Aggregate aggregate) return aggregate(aggregate);
        throw new IllegalArgumentException("type '" + type.displayName() + "' has no object layout");
    }

    private TypeLayout aggregate(Type.Aggregate aggregate) {
        List<TypeLayout.Member> members = new ArrayList<>();
        List<TypeLayout.Padding> padding = new ArrayList<>();
        long cursor = 0;
        long maximum = 0;
        int aggregateAlignment = 1;
        for (Type.Field field : aggregate.fields()) {
            TypeLayout fieldLayout = layout(field.type());
            aggregateAlignment = Math.max(aggregateAlignment, fieldLayout.alignment());
            long offset;
            if (aggregate.kind() == Type.AggregateKind.STRUCT) {
                offset = align(cursor, fieldLayout.alignment());
                if (offset > cursor) padding.add(new TypeLayout.Padding(cursor, offset - cursor));
                cursor = add(offset, fieldLayout.size(), aggregate);
                maximum = cursor;
            } else {
                offset = 0;
                maximum = Math.max(maximum, fieldLayout.size());
            }
            members.add(new TypeLayout.Member(field.name(), field.type(), offset,
                fieldLayout.size(), fieldLayout.alignment()));
        }
        long size = align(maximum, aggregateAlignment);
        if (aggregate.kind() == Type.AggregateKind.STRUCT && size > maximum) {
            padding.add(new TypeLayout.Padding(maximum, size - maximum));
        }
        return new TypeLayout(aggregate, size, aggregateAlignment, members, padding);
    }

    public long fieldOffset(Type.Aggregate aggregate, String fieldName) {
        return layout(aggregate).members().stream()
            .filter(member -> member.name().equals(fieldName)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "unknown field '" + fieldName + "' in " + aggregate.name())).offset();
    }

    public String describe(Type type) {
        TypeLayout value = layout(type);
        StringBuilder out = new StringBuilder();
        if (type instanceof Type.Aggregate aggregate) {
            out.append(aggregate.kind().keyword()).append(' ').append(aggregate.name());
        } else out.append(type.displayName());
        out.append(": size=").append(value.size()).append(", align=")
            .append(value.alignment()).append('\n');
        int paddingIndex = 0;
        for (TypeLayout.Member member : value.members()) {
            while (paddingIndex < value.padding().size()
                    && value.padding().get(paddingIndex).offset() < member.offset()) {
                appendPadding(out, value.padding().get(paddingIndex++));
            }
            out.append(String.format("  %4d  %4d  align %-2d  %-16s %s%n",
                member.offset(), member.size(), member.alignment(), member.name(),
                member.type().displayName()));
        }
        while (paddingIndex < value.padding().size()) {
            appendPadding(out, value.padding().get(paddingIndex++));
        }
        return out.toString();
    }

    private static void appendPadding(StringBuilder out, TypeLayout.Padding gap) {
        out.append(String.format("  %4d  %4d  padding%n", gap.offset(), gap.size()));
    }

    private static TypeLayout scalar(Type type, long size, int alignment) {
        return new TypeLayout(type, size, alignment, List.of(), List.of());
    }
    private static long align(long value, int alignment) {
        try {
            return Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1L), alignment), alignment);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("type layout overflows 64-bit size");
        }
    }
    private static long add(long left, long right, Type type) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException exception) {
            throw new IllegalArgumentException("layout of '" + type.displayName() + "' is too large");
        }
    }
    private static long multiply(long left, long right, Type type) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) {
            throw new IllegalArgumentException("layout of '" + type.displayName() + "' is too large");
        }
    }
}
