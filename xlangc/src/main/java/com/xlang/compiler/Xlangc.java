package com.xlang.compiler;

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
 * <p>This class exists in P0 only so the module has something to compile and
 * so the CLI can already import it. It carries no logic yet.
 */
public final class Xlangc {
    private Xlangc() {}

    /** Marker for tests: proves the module is on the classpath. */
    public static String greeting() {
        return "xlangc P0 scaffold ready";
    }
}
