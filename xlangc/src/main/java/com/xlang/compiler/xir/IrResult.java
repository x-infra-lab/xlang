package com.xlang.compiler.xir;

import com.xlang.compiler.diag.Diagnostic;
import java.util.List;

/** Result of lexing, parsing, checking, and lowering a source module. */
public record IrResult(Xir.Module module, List<Diagnostic> diagnostics) {
    public IrResult { diagnostics = List.copyOf(diagnostics); }
    public boolean hasErrors() { return !diagnostics.isEmpty(); }
}
