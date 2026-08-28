package com.xlang.compiler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.object.XObject;
import com.xlang.compiler.object.XObjectIO;
import com.xlang.vm.Opcode;
import com.xlang.vm.XMachine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendTest {
    @Test void generatedFunctionsExecuteWithCallsArgumentsLoopsAndComparisons() {
        String source = """
            fn combine(a: int, b: int, c: int, d: int, e: int) -> int {
                return a + b + c + d + e;
            }
            fn main() -> int {
                let n = 0;
                while (n < 5) { n += 1; }
                if (n == 5) { return combine(n, 2, 3, 4, 5); }
                return 0;
            }
            """;
        var result = Xlangc.compile(source);
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        byte[] program = linkSingleTextObject(result.object(), "main");
        XMachine machine = new XMachine();
        machine.load(program);
        machine.run();
        assertEquals(19, machine.cpu().register(0));
        assertEquals(com.xlang.vm.XOS.STACK_TOP, machine.cpu().register(7));
    }

    @Test void emitsGlobalStringAndControlFlowRelocations() {
        String source = """
            let greeting = "hello";
            fn pick(x: int) -> bool { return x >= 2; }
            fn main() -> int { if (pick(3)) { return 1; } else { return 0; } }
            """;
        XObject object = Xlangc.compile(source).object();
        assertTrue(object.symbols().stream().anyMatch(s -> s.name().equals("greeting")
            && s.section() == XObject.Section.DATA && s.global()));
        XObject.Symbol string = object.symbols().stream().filter(s -> s.name().startsWith("$str.")).findFirst().orElseThrow();
        assertEquals("hello\0", new String(Arrays.copyOfRange(object.data(), string.offset(),
            string.offset() + string.size()), StandardCharsets.UTF_8));
        assertTrue(object.relocations().stream().anyMatch(r -> r.symbol().equals("greeting")
            && r.type() == XObject.RelocationType.ABS64));
        assertTrue(object.relocations().stream().anyMatch(r -> r.symbol().equals("pick")
            && r.type() == XObject.RelocationType.ABS32));
        assertTrue(object.relocations().stream().anyMatch(r -> r.symbol().startsWith("$L.")
            && r.type() == XObject.RelocationType.ABS32));
    }

    @Test void executesEveryComparisonUnaryAndRemainingArithmeticFamily() {
        String source = """
            fn main() -> int {
                if (!(1 < 2)) { return 1; }
                if (1 != 1) { return 2; }
                if (2 <= 2) {} else { return 3; }
                if (2 > 1) {} else { return 4; }
                if (2 >= 2) {} else { return 5; }
                let n = -10;
                n = n / 2;
                n %= 3;
                return n + 44;
            }
            """;
        XObject object = Xlangc.compile(source).object();
        XMachine machine = new XMachine();
        machine.load(linkSingleTextObject(object, "main"));
        machine.run();
        assertEquals(42, machine.cpu().register(0));
    }

    @Test void objectFormatRoundTripsExactly(@TempDir Path directory) throws Exception {
        XObject original = Xlangc.compile("fn main() -> int { return 40 + 2; }").object();
        Path path = directory.resolve("main.xo");
        XObjectIO.write(path, original);
        XObject restored = XObjectIO.read(path);
        assertArrayEquals(original.text(), restored.text());
        assertArrayEquals(original.data(), restored.data());
        assertEquals(original.symbols(), restored.symbols());
        assertEquals(original.relocations(), restored.relocations());
    }

    @Test void invalidSourceDoesNotProducePartialObject() {
        var result = Xlangc.compile("fn main() -> int { return false; }");
        assertTrue(result.hasErrors());
        assertNull(result.object());
    }

    private static byte[] linkSingleTextObject(XObject object, String entry) {
        int prefix = 6; // call abs32 + halt
        byte[] text = object.text();
        byte[] program = new byte[prefix + text.length];
        program[0] = (byte) Opcode.CALL.code();
        program[5] = (byte) Opcode.HALT.code();
        System.arraycopy(text, 0, program, prefix, text.length);
        Map<String, XObject.Symbol> symbols = object.symbols().stream()
            .collect(Collectors.toMap(XObject.Symbol::name, symbol -> symbol));
        writeI32(program, 1, prefix + symbols.get(entry).offset());
        for (XObject.Relocation relocation : object.relocations()) {
            if (relocation.section() != XObject.Section.TEXT) continue;
            XObject.Symbol symbol = symbols.get(relocation.symbol());
            if (symbol == null || symbol.section() != XObject.Section.TEXT) {
                throw new AssertionError("test linker cannot resolve " + relocation.symbol());
            }
            long value = prefix + symbol.offset() + relocation.addend();
            int at = prefix + relocation.offset();
            if (relocation.type() == XObject.RelocationType.ABS32) writeI32(program, at, (int) value);
            else writeI64(program, at, value);
        }
        return program;
    }

    private static void writeI32(byte[] target, int at, int value) {
        for (int shift = 0; shift < 32; shift += 8) target[at + shift / 8] = (byte) (value >>> shift);
    }

    private static void writeI64(byte[] target, int at, long value) {
        for (int shift = 0; shift < 64; shift += 8) target[at + shift / 8] = (byte) (value >>> shift);
    }
}
