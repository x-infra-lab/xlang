package com.xlang.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.sema.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypeCheckerTest {
    @Test void checksValidProgramAndAssignsEveryExpressionAType() {
        String source = """
            fn add(a: int, b: int) -> int { return a + b; }
            fn main() -> int {
                let sum = add(40, 2);
                let ready: bool = sum == 42;
                if (ready) { return sum; } else { return 0; }
            }
            """;
        var result = Xlangc.check(source);
        assertFalse(result.hasErrors(), messages(result.diagnostics()));
        Ast.FnDecl main = (Ast.FnDecl) result.program().items().get(1);
        Ast.LetDecl sum = (Ast.LetDecl) main.body().statements().get(0);
        assertEquals(Type.INT, result.typeCheck().typeOf(sum.initializer()));
        assertTrue(result.typeCheck().expressionTypes().size() >= 10);
    }

    @Test void supportsLexicalShadowingButRejectsSameScopeDuplicates() {
        String valid = "fn main() -> int { let x: int = 1; { let x: bool = true; x; } return x; }";
        assertFalse(Xlangc.check(valid).hasErrors(), messages(Xlangc.check(valid).diagnostics()));

        var invalid = Xlangc.check("fn main() -> int { let x = 1; let x = 2; return x; }");
        assertMessage(invalid.diagnostics(), "duplicate declaration");
    }

    @Test void resolvesForwardFunctionCallsAndChecksArguments() {
        String valid = "fn main() -> int { return twice(21); } fn twice(x: int) -> int { return x * 2; }";
        assertFalse(Xlangc.check(valid).hasErrors(), messages(Xlangc.check(valid).diagnostics()));

        var invalid = Xlangc.check("fn f(x: int) -> int { return x; } fn main() -> int { return f(true, 2); }");
        assertMessage(invalid.diagnostics(), "expects 1 arguments but got 2");
        assertMessage(invalid.diagnostics(), "argument 1 expects int but got bool");
    }

    @Test void functionsCanResolveLaterInferredGlobals() {
        String source = "fn main() -> int { return answer; } let answer = 42;";
        assertFalse(Xlangc.check(source).hasErrors(), messages(Xlangc.check(source).diagnostics()));
    }

    @Test void rejectsInvalidOperatorsConditionsAssignmentsAndNames() {
        String source = """
            fn main() -> int {
                let n: int = true;
                if (1) { n = false; }
                let b = true + 1;
                missing;
                return 0;
            }
            """;
        var result = Xlangc.check(source);
        assertMessage(result.diagnostics(), "initializer expects int but got bool");
        assertMessage(result.diagnostics(), "if condition requires bool but got int");
        assertMessage(result.diagnostics(), "assignment expects int but got bool");
        assertMessage(result.diagnostics(), "left operand requires int but got bool");
        assertMessage(result.diagnostics(), "undefined name 'missing'");
    }

    @Test void validatesReturnsEntryPointAndLoopControl() {
        var wrongMain = Xlangc.check("fn main(x: int) -> bool { return 1; }");
        assertMessage(wrongMain.diagnostics(), "entry point must have signature");
        assertMessage(wrongMain.diagnostics(), "return value expects bool but got int");

        var missingReturn = Xlangc.check("fn main() -> int { let x = 1; }");
        assertMessage(missingReturn.diagnostics(), "may complete without returning int");

        var breakOutside = Xlangc.check("fn main() -> int { break; continue; return 0; }");
        assertEquals(2, breakOutside.diagnostics().stream().filter(d -> d.message().contains("only valid inside a loop")).count());
    }

    @Test void reportsP9FeaturesWithoutCascadingTypeNoise() {
        var result = Xlangc.check("fn main() -> int { let p: *int = null; return 0; }");
        assertMessage(result.diagnostics(), "pointer and array types are implemented in P9");
        assertMessage(result.diagnostics(), "null requires pointer types, which are implemented in P9");
    }

    private static void assertMessage(List<com.xlang.compiler.diag.Diagnostic> diagnostics, String fragment) {
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains(fragment)), messages(diagnostics));
    }

    private static String messages(List<com.xlang.compiler.diag.Diagnostic> diagnostics) {
        return diagnostics.stream().map(com.xlang.compiler.diag.Diagnostic::message).toList().toString();
    }
}
