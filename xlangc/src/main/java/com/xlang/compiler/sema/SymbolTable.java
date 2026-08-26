package com.xlang.compiler.sema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** A lexical scope with a link to its enclosing scope. */
public final class SymbolTable {
    private final SymbolTable parent;
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();

    public SymbolTable() {
        this(null);
    }

    public SymbolTable(SymbolTable parent) {
        this.parent = parent;
    }

    public SymbolTable child() {
        return new SymbolTable(this);
    }

    /** Defines a name in this scope; returns false for a same-scope duplicate. */
    public boolean define(Symbol symbol) {
        return symbols.putIfAbsent(symbol.name(), symbol) == null;
    }

    public Optional<Symbol> lookupLocal(String name) {
        return Optional.ofNullable(symbols.get(name));
    }

    public Optional<Symbol> lookup(String name) {
        Symbol symbol = symbols.get(name);
        if (symbol != null) return Optional.of(symbol);
        return parent == null ? Optional.empty() : parent.lookup(name);
    }
}
