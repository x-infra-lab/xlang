package com.xlang.compiler.diag;

import com.xlang.compiler.source.SourceSpan;

/**
 * A single compiler diagnostic: an error or warning tied to a source span.
 *
 * <p>P1 only emits {@link Severity#ERROR}; warnings are declared here to
 * keep the type stable for P2 which will surface a couple of type-check
 * warnings.
 */
public record Diagnostic(Severity severity, SourceSpan span, String message) {

    public enum Severity { ERROR, WARNING }

    public static Diagnostic error(SourceSpan span, String message) {
        return new Diagnostic(Severity.ERROR, span, message);
    }

    public String format(String fileName) {
        return fileName + ":" + span.startLine() + ":" + span.startColumn()
            + ": " + severity.name().toLowerCase() + ": " + message;
    }
}
