package com.xlang.compiler;

import com.xlang.compiler.lex.LexResult;
import com.xlang.compiler.lex.Lexer;
import com.xlang.compiler.parse.ParseResult;
import com.xlang.compiler.parse.Parser;
import com.xlang.compiler.sema.CheckResult;
import com.xlang.compiler.sema.TypeCheckResult;
import com.xlang.compiler.sema.TypeChecker;
import com.xlang.compiler.xir.IrResult;
import com.xlang.compiler.xir.Lowerer;
import com.xlang.compiler.backend.CompileResult;
import com.xlang.compiler.backend.XBackend;
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
        return check(source, true);
    }

    private static CheckResult check(String source, boolean requireMain) {
        ParseResult parsed = parse(source);
        if (parsed.hasErrors()) {
            TypeCheckResult skipped = new TypeCheckResult(java.util.List.of(), new IdentityHashMap<>());
            return new CheckResult(parsed.program(), parsed.diagnostics(), skipped);
        }
        TypeCheckResult checked = new TypeChecker(parsed.program(), requireMain).check();
        return new CheckResult(parsed.program(), checked.diagnostics(), checked);
    }

    public static IrResult lower(String source) {
        CheckResult checked = check(source);
        if (checked.hasErrors()) return new IrResult(null, checked.diagnostics());
        return new IrResult(new Lowerer(checked.program(), checked.typeCheck()).lower(), java.util.List.of());
    }

    public static CompileResult compile(String source) {
        return compile(source, true);
    }

    /** Compiles a library that may reference the application's external main function. */
    public static CompileResult compileLibrary(String source) {
        return compile(source, false);
    }

    private static CompileResult compile(String source, boolean requireMain) {
        CheckResult checked = check(source, requireMain);
        if (checked.hasErrors()) return new CompileResult(null, checked.diagnostics());
        IrResult lowered = new IrResult(new Lowerer(checked.program(), checked.typeCheck()).lower(),
            java.util.List.of());
        if (lowered.hasErrors()) return new CompileResult(null, lowered.diagnostics());
        return new CompileResult(new XBackend().compile(lowered.module()), java.util.List.of());
    }
}
