package com.xlang.compiler.backend;

import com.xlang.compiler.diag.Diagnostic;
import com.xlang.compiler.object.XObject;
import java.util.List;

/** Result of the complete source-to-object P6 pipeline. */
public record CompileResult(XObject object, List<Diagnostic> diagnostics) {
    public CompileResult { diagnostics = List.copyOf(diagnostics); }
    public boolean hasErrors() { return !diagnostics.isEmpty(); }
}
