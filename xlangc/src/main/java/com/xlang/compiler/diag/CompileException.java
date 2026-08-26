package com.xlang.compiler.diag;

import java.util.List;

/**
 * Thrown when one or more diagnostics prevent lex or parse from producing
 * a useful result. The parser prefers to accumulate diagnostics and keep
 * going, so this is only thrown at the boundary (e.g. by the CLI) when
 * the caller wants a fail-fast surface.
 */
public class CompileException extends RuntimeException {

    private final List<Diagnostic> diagnostics;

    public CompileException(List<Diagnostic> diagnostics) {
        super(diagnostics.isEmpty() ? "compilation failed" : diagnostics.get(0).message());
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }
}
