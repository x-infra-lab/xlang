package com.xlang.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.xir.Xir;
import com.xlang.compiler.xir.XirPrinter;
import java.util.List;
import org.junit.jupiter.api.Test;

class XirLowererTest {
    @Test void lowersExpressionsIntoThreeAddressInstructions() {
        var result = Xlangc.lower("fn main() -> int { let x = 1 + 2 * 3; return x; }");
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        Xir.Function main = function(result.module(), "main");
        Xir.BasicBlock entry = main.blocks().get(0);
        assertEquals(6, entry.instructions().size());
        assertEquals(3, entry.instructions().stream().filter(Xir.Const.class::isInstance).count());
        assertEquals(2, entry.instructions().stream().filter(Xir.Binary.class::isInstance).count());
        assertInstanceOf(Xir.Return.class, entry.terminator());
        String printed = XirPrinter.print(result.module());
        assertTrue(printed.indexOf("star") < printed.indexOf("plus"), printed);
    }

    @Test void lowersIfWhileBreakContinueAndShortCircuitToBlocks() {
        String source = """
            fn main() -> int {
                let x = 0;
                while (x < 10) {
                    x += 1;
                    if (x == 2) { continue; }
                    if (x == 8 && true) { break; }
                }
                return x;
            }
            """;
        var result = Xlangc.lower(source);
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        Xir.Function main = function(result.module(), "main");
        assertTrue(main.blocks().size() >= 12);
        assertTrue(main.blocks().stream().anyMatch(b -> b.label().startsWith("while.cond")));
        assertTrue(main.blocks().stream().anyMatch(b -> b.label().startsWith("logic.rhs")));
        assertTrue(main.blocks().stream().anyMatch(b -> b.terminator() instanceof Xir.Branch));
        assertTrue(main.blocks().stream().allMatch(b -> b.terminator() != null));
    }

    @Test void givesShadowedLocalsDistinctVirtualNames() {
        String source = "fn main() -> int { let x = 1; { let x = 2; x; } return x; }";
        Xir.Function main = function(Xlangc.lower(source).module(), "main");
        List<String> targets = main.blocks().stream().flatMap(b -> b.instructions().stream())
            .filter(Xir.Copy.class::isInstance).map(Xir.Copy.class::cast)
            .map(copy -> copy.target().name()).filter(name -> name.startsWith("%x.")).toList();
        assertEquals(List.of("%x.0", "%x.1"), targets);
    }

    @Test void lowersGlobalsThroughModuleInitializer() {
        String source = "let answer = seed(); fn seed() -> int { return 42; } fn main() -> int { return answer; }";
        var result = Xlangc.lower(source);
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(List.of("answer"), result.module().globals().stream().map(Xir.Global::name).toList());
        Xir.Function initializer = function(result.module(), "$module_init");
        assertTrue(initializer.blocks().get(0).instructions().stream().anyMatch(Xir.Call.class::isInstance));
        assertTrue(initializer.blocks().get(0).instructions().stream()
            .filter(Xir.Copy.class::isInstance).map(Xir.Copy.class::cast)
            .anyMatch(copy -> copy.target().name().equals("@answer")));
    }

    @Test void voidCallsHaveNoResultAndInvalidProgramsDoNotLower() {
        String valid = "fn log() -> void { return; } fn main() -> int { log(); return 0; }";
        var lowered = Xlangc.lower(valid);
        Xir.Call call = function(lowered.module(), "main").blocks().get(0).instructions().stream()
            .filter(Xir.Call.class::isInstance).map(Xir.Call.class::cast).findFirst().orElseThrow();
        assertNull(call.result());

        var invalid = Xlangc.lower("fn main() -> int { return true; }");
        assertTrue(invalid.hasErrors());
        assertNull(invalid.module());
        assertNotNull(invalid.diagnostics().get(0).span());
    }

    private static Xir.Function function(Xir.Module module, String name) {
        return module.functions().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }
}
