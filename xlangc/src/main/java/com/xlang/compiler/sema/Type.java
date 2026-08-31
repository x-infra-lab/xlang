package com.xlang.compiler.sema;

import java.util.List;
import java.util.Objects;

/** Semantic types, including P9 pointers, arrays, structs, and unions. */
public sealed interface Type permits Type.Primitive, Type.Pointer, Type.Array,
        Type.Aggregate, Type.Error, Type.Null {
    Type INT = Primitive.INT;
    Type BOOL = Primitive.BOOL;
    Type VOID = Primitive.VOID;
    Type STRING = Primitive.STRING;
    Type ERROR = Error.INSTANCE;
    Type NULL = Null.INSTANCE;

    String displayName();

    default boolean aggregateValue() {
        return this instanceof Array || this instanceof Aggregate;
    }

    static Pointer pointer(Type target) { return new Pointer(target); }
    static Array array(long length, Type element) { return new Array(length, element); }

    enum Primitive implements Type {
        INT("int"), BOOL("bool"), VOID("void"), STRING("string");
        private final String displayName;
        Primitive(String displayName) { this.displayName = displayName; }
        @Override public String displayName() { return displayName; }
    }

    record Pointer(Type target) implements Type {
        public Pointer { Objects.requireNonNull(target, "pointer target"); }
        @Override public String displayName() { return "*" + target.displayName(); }
    }

    record Array(long length, Type element) implements Type {
        public Array {
            if (length <= 0) throw new IllegalArgumentException("array length must be positive");
            Objects.requireNonNull(element, "array element");
        }
        @Override public String displayName() { return "[" + length + "]" + element.displayName(); }
    }

    /** Nominal aggregate. Identity, not field equality, determines type equality. */
    final class Aggregate implements Type {
        private final AggregateKind kind;
        private final String name;
        private List<Field> fields;

        public Aggregate(AggregateKind kind, String name) {
            this.kind = Objects.requireNonNull(kind, "aggregate kind");
            this.name = Objects.requireNonNull(name, "aggregate name");
        }
        public AggregateKind kind() { return kind; }
        public String name() { return name; }
        public List<Field> fields() {
            if (fields == null) throw new IllegalStateException("aggregate '" + name + "' is incomplete");
            return fields;
        }
        public void define(List<Field> definition) {
            if (fields != null) throw new IllegalStateException("aggregate '" + name + "' is already defined");
            fields = List.copyOf(definition);
        }
        public Field field(String fieldName) {
            return fields().stream().filter(field -> field.name().equals(fieldName)).findFirst().orElse(null);
        }
        @Override public String displayName() { return name; }
        @Override public String toString() { return kind.keyword() + " " + name; }
    }

    enum AggregateKind {
        STRUCT("struct"), UNION("union");
        private final String keyword;
        AggregateKind(String keyword) { this.keyword = keyword; }
        public String keyword() { return keyword; }
    }

    record Field(String name, Type type) {
        public Field {
            Objects.requireNonNull(name, "field name");
            Objects.requireNonNull(type, "field type");
        }
    }

    enum Error implements Type {
        INSTANCE;
        @Override public String displayName() { return "<error>"; }
    }

    enum Null implements Type {
        INSTANCE;
        @Override public String displayName() { return "null"; }
    }
}
