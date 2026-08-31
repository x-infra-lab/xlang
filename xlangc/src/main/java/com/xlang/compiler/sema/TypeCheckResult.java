package com.xlang.compiler.sema;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostics plus expression types, resolved type syntax, and P9 aggregates. */
public record TypeCheckResult(List<Diagnostic> diagnostics, Map<Ast.Expr, Type> expressionTypes,
                              Map<Ast.TypeRef, Type> resolvedTypes,
                              Map<String, Type.Aggregate> aggregates) {
    public TypeCheckResult {
        diagnostics = List.copyOf(diagnostics);
        expressionTypes = Collections.unmodifiableMap(new IdentityHashMap<>(expressionTypes));
        resolvedTypes = Collections.unmodifiableMap(new IdentityHashMap<>(resolvedTypes));
        aggregates = Map.copyOf(aggregates);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    public Type typeOf(Ast.Expr expression) {
        return expressionTypes.get(expression);
    }

    public Type resolvedType(Ast.TypeRef reference) { return resolvedTypes.get(reference); }
}
