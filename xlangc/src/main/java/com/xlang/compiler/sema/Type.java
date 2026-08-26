package com.xlang.compiler.sema;

/** Semantic types understood by the P2 checker. */
public enum Type {
    INT("int"), BOOL("bool"), VOID("void"), STRING("string"), ERROR("<error>");

    private final String displayName;

    Type(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
