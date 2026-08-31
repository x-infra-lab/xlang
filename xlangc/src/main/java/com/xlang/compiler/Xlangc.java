package com.xlang.compiler;

import com.xlang.compiler.lex.LexResult;
import com.xlang.compiler.lex.Lexer;
import com.xlang.compiler.parse.ParseResult;
import com.xlang.compiler.parse.Parser;
import com.xlang.compiler.sema.CheckResult;
import com.xlang.compiler.sema.TypeCheckResult;
import com.xlang.compiler.sema.TypeChecker;
import com.xlang.compiler.sema.LayoutEngine;
import com.xlang.compiler.sema.LayoutResult;
import com.xlang.compiler.sema.Type;
import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
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
 *   <li>P9 -- aggregate types, checked pointers, and explicit layout.</li>
 * </ul>
 *
 * <p>This small facade is the public compiler boundary used by the CLI.
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
            TypeCheckResult skipped = new TypeCheckResult(java.util.List.of(),
                new IdentityHashMap<>(), new IdentityHashMap<>(), java.util.Map.of());
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

    /** Parses and lays out a standalone type or one inline struct/union declaration. */
    public static LayoutResult layout(String query) {
        String trimmed = query.trim();
        boolean declaration = trimmed.startsWith("struct ") || trimmed.startsWith("union ");
        String source = declaration
            ? trimmed + "\nfn main() -> int { return 0; }"
            : "fn __layout(value: " + trimmed + ") -> void { return; }\n"
                + "fn main() -> int { return 0; }";
        CheckResult checked = check(source);
        if (checked.hasErrors()) return new LayoutResult(null, "", checked.diagnostics());
        Type type;
        Ast.TypeRef reference;
        if (declaration) {
            Ast.AggregateDecl aggregate = checked.program().items().stream()
                .filter(Ast.AggregateDecl.class::isInstance).map(Ast.AggregateDecl.class::cast)
                .findFirst().orElseThrow();
            type = checked.typeCheck().aggregates().get(aggregate.name());
            reference = null;
        } else {
            Ast.FnDecl function = checked.program().items().stream()
                .filter(Ast.FnDecl.class::isInstance).map(Ast.FnDecl.class::cast)
                .filter(item -> item.name().equals("__layout")).findFirst().orElseThrow();
            reference = function.params().get(0).type();
            type = checked.typeCheck().resolvedType(reference);
        }
        try {
            LayoutEngine engine = new LayoutEngine();
            return new LayoutResult(engine.layout(type), engine.describe(type), java.util.List.of());
        } catch (IllegalArgumentException exception) {
            var span = reference == null ? checked.program().span() : reference.span();
            return new LayoutResult(null, "", java.util.List.of(Diagnostic.error(span,
                exception.getMessage())));
        }
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
