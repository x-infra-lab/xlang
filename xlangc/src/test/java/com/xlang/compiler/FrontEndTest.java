package com.xlang.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.print.AstPrinter;
import com.xlang.compiler.token.TokenType;
import org.junit.jupiter.api.Test;

class FrontEndTest {
    @Test void lexesKeywordsNumbersCommentsAndEveryOperator() {
        var r = Xlangc.lex("fn x(){// c\n let n=0x2a+10; /* b */ n += 1; n==2 && n!=3 || !false; &n | n; }");
        assertFalse(r.hasErrors(), r.diagnostics().toString());
        assertTrue(r.tokens().stream().anyMatch(t -> t.type() == TokenType.FN));
        assertTrue(r.tokens().stream().anyMatch(t -> t.type() == TokenType.INT_LIT && t.asInt() == 42));
        assertTrue(r.tokens().stream().anyMatch(t -> t.type() == TokenType.PLUS_ASSIGN));
        assertTrue(r.tokens().stream().anyMatch(t -> t.type() == TokenType.AMP_AMP));
        assertTrue(r.tokens().stream().anyMatch(t -> t.type() == TokenType.PIPE));
    }

    @Test void decodesStringEscapesAndUnicode() {
        var r = Xlangc.lex("\"你好\\n\\t\\0\\\"\\\\\"");
        assertFalse(r.hasErrors(), r.diagnostics().toString());
        assertEquals("你好\n\t\0\"\\", r.tokens().get(0).asString());
    }

    @Test void rejectsOverflowBadEscapeAndUnterminatedComment() {
        assertTrue(Xlangc.lex("9223372036854775808").hasErrors());
        assertTrue(Xlangc.lex("\"\\q\"").hasErrors());
        assertTrue(Xlangc.lex("/* nope").hasErrors());
    }

    @Test void parsesSpecificationExample() {
        String source = """
            fn add(a: int, b: int) -> int { return a + b; }
            fn main() -> int {
              let x: int = 40;
              let y: int = 2;
              return add(x, y);
            }
            """;
        var r = Xlangc.parse(source);
        assertFalse(r.hasErrors(), r.diagnostics().toString());
        assertEquals(2, r.program().items().size());
        assertTrue(AstPrinter.print(r.program()).contains("FnDecl main"));
    }

    @Test void honoursPrecedenceAndRightAssociativeAssignment() {
        var r = Xlangc.parse("fn f(){ a = b = 1 + 2 * 3 == 7 || false; }");
        assertFalse(r.hasErrors(), r.diagnostics().toString());
        var fn = (Ast.FnDecl) r.program().items().get(0);
        var outer = assertInstanceOf(Ast.AssignExpr.class, ((Ast.ExprStmt) fn.body().statements().get(0)).expression());
        assertInstanceOf(Ast.AssignExpr.class, outer.value());
        assertEquals(TokenType.ASSIGN, outer.operator());
        String printed = AstPrinter.print(r.program());
        assertTrue(printed.indexOf("Binary STAR") > printed.indexOf("Binary PLUS"));
    }

    @Test void parsesAllStatementPostfixAndFutureTypeForms() {
        String source = """
            let global: *int = null;
            fn f(a: [4]int) -> void {
              while (true) { if (a[0].x) { break; } else if (false) { continue; } else {} }
              foo(1, 2).bar[0]; return;
            }
            """;
        var r = Xlangc.parse(source);
        assertFalse(r.hasErrors(), r.diagnostics().toString());
    }

    @Test void recoversAndReportsAtLeastFourErrors() {
        String source = """
            fn broken(a int) { let = ; return ; }
            let x = ;
            fn also() { if true { nope } let y = 2 return y; }
            fn good() { return 0; }
            """;
        var r = Xlangc.parse(source);
        assertTrue(r.diagnostics().size() >= 4, r.diagnostics().toString());
        assertTrue(r.program().items().stream().anyMatch(i -> i instanceof Ast.FnDecl f && f.name().equals("good")));
    }
}
