package com.xlang.compiler.parse;
import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
import java.util.List;
public record ParseResult(Ast.Program program, List<Diagnostic> diagnostics) {
    public ParseResult { diagnostics = List.copyOf(diagnostics); }
    public boolean hasErrors() { return !diagnostics.isEmpty(); }
}
