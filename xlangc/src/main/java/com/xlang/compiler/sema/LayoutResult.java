package com.xlang.compiler.sema;

import com.xlang.compiler.diag.Diagnostic;
import java.util.List;

/** Result of a standalone P9 layout query. */
public record LayoutResult(TypeLayout layout, String description, List<Diagnostic> diagnostics) {
    public LayoutResult { diagnostics = List.copyOf(diagnostics); }
    public boolean hasErrors() { return !diagnostics.isEmpty(); }
}
