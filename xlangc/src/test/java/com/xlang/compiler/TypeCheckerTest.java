package com.xlang.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.sema.Type;
import com.xlang.compiler.sema.LayoutEngine;
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

    @Test void checksPointersArraysAggregatesAndTheirLayouts() {
        String source = """
            struct Node { next: *Node; value: int; }
            struct Pair { flag: bool; left: int; right: int; }
            union Value { integer: int; truth: bool; }
            fn main() -> int {
              let pair = Pair { flag: true, left: 40, right: 2 };
              let values: [3]int = [1, 2, 3];
              let pointer: *int = &pair.left;
              *pointer = values[0];
              let empty: *Pair = null;
              if (empty == null) { return sizeof(Pair); }
              return 0;
            }
            """;
        var result = Xlangc.check(source);
        assertFalse(result.hasErrors(), messages(result.diagnostics()));
        Type.Aggregate pair = result.typeCheck().aggregates().get("Pair");
        var layout = new LayoutEngine().layout(pair);
        assertEquals(24, layout.size());
        assertEquals(8, layout.alignment());
        assertEquals(0, layout.members().get(0).offset());
        assertEquals(8, layout.members().get(1).offset());
        assertEquals(16, layout.members().get(2).offset());
        Type.Aggregate value = result.typeCheck().aggregates().get("Value");
        assertEquals(8, new LayoutEngine().layout(value).size());
        assertTrue(new LayoutEngine().layout(value).members().stream()
            .allMatch(member -> member.offset() == 0));
    }

    @Test void rejectsBadAggregateInitializersAndRecursiveByValueLayout() {
        var result = Xlangc.check("""
            struct Bad { self: Bad; }
            struct Pair { x: int; y: bool; }
            fn main() -> int {
              let pair = Pair { x: true, x: 1 };
              return 0;
            }
            """);
        assertMessage(result.diagnostics(), "recursive by-value type");
        assertMessage(result.diagnostics(), "initialized more than once");
        assertMessage(result.diagnostics(), "field 'x' expects int but got bool");
        assertMessage(result.diagnostics(), "missing field 'y'");
    }

    private static void assertMessage(List<com.xlang.compiler.diag.Diagnostic> diagnostics, String fragment) {
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains(fragment)), messages(diagnostics));
    }

    private static String messages(List<com.xlang.compiler.diag.Diagnostic> diagnostics) {
        return diagnostics.stream().map(com.xlang.compiler.diag.Diagnostic::message).toList().toString();
    }
}
