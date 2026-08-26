package com.xlang.compiler.sema;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostics and the inferred type of every expression visited by P2. */
public record TypeCheckResult(List<Diagnostic> diagnostics, Map<Ast.Expr, Type> expressionTypes) {
    public TypeCheckResult {
        diagnostics = List.copyOf(diagnostics);
        expressionTypes = Collections.unmodifiableMap(new IdentityHashMap<>(expressionTypes));
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    public Type typeOf(Ast.Expr expression) {
        return expressionTypes.get(expression);
    }
}
