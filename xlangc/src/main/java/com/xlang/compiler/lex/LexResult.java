package com.xlang.compiler.lex;

import com.xlang.compiler.diag.Diagnostic;
import com.xlang.compiler.token.Token;

import java.util.List;

/**
 * Output of {@link Lexer#lex()}: the token stream plus every diagnostic
 * the lexer produced.
 *
 * <p>The token list always ends with a synthetic {@code EOF} token so the
 * parser can look one past the end without a bounds check. The
 * diagnostics list may be empty (well-formed input) or non-empty even
 * when {@link #tokens()} is complete: the lexer recovers past most
 * errors so downstream code can still make progress.
 */
public record LexResult(List<Token> tokens, List<Diagnostic> diagnostics) {

    public LexResult {
        tokens = List.copyOf(tokens);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream()
            .anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }
}
