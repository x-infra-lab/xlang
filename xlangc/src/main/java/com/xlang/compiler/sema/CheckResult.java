package com.xlang.compiler.sema;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
import java.util.List;

/** Result of the complete lex + parse + type-check pipeline. */
public record CheckResult(Ast.Program program, List<Diagnostic> diagnostics,
                          TypeCheckResult typeCheck) {
    public CheckResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }
}
