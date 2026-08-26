package com.xlang.compiler;

import com.xlang.compiler.lex.LexResult;
import com.xlang.compiler.lex.Lexer;
import com.xlang.compiler.parse.ParseResult;
import com.xlang.compiler.parse.Parser;
import com.xlang.compiler.sema.CheckResult;
import com.xlang.compiler.sema.TypeCheckResult;
import com.xlang.compiler.sema.TypeChecker;
import java.util.IdentityHashMap;

/**
 * xlangc: the xlang compiler.
 *
 * <p>Responsibilities across phases:
 * <ul>
 *   <li>P1 -- hand-written {@code Lexer} producing a {@code Token} stream.</li>
 *   <li>P1 -- recursive-descent {@code Parser} producing an {@code Ast}.</li>
 *   <li>P2 -- {@code TypeChecker} + {@code SymbolTable} scopes.</li>
 *   <li>P3 -- lowering to {@code XIR} (three-address form, basic blocks).</li>
 *   <li>P6 -- backend: XIR -> XMachine machine code, emit {@code .xo} objects.</li>
 * </ul>
 *
 * <p>This small facade is the public P1 front-end boundary used by the CLI.
 */
public final class Xlangc {
    private Xlangc() {}

    public static LexResult lex(String source) { return new Lexer(source).lex(); }
    public static ParseResult parse(String source) {
        LexResult lexed = lex(source);
        ParseResult parsed = new Parser(lexed.tokens()).parse();
        if (lexed.diagnostics().isEmpty()) return parsed;
        var all = new java.util.ArrayList<>(lexed.diagnostics()); all.addAll(parsed.diagnostics());
        return new ParseResult(parsed.program(), all);
    }

    public static CheckResult check(String source) {
        ParseResult parsed = parse(source);
        if (parsed.hasErrors()) {
            TypeCheckResult skipped = new TypeCheckResult(java.util.List.of(), new IdentityHashMap<>());
            return new CheckResult(parsed.program(), parsed.diagnostics(), skipped);
        }
        TypeCheckResult checked = new TypeChecker(parsed.program()).check();
        return new CheckResult(parsed.program(), checked.diagnostics(), checked);
    }
}
