package com.xlang.linker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.Xlangc;
import com.xlang.compiler.object.XObject;
import com.xlang.vm.Assembler;
import com.xlang.vm.Opcode;
import com.xlang.vm.Protection;
import com.xlang.vm.XMachine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XldTest {
    @Test
    void startupRunsModuleInitializersBeforeMain() throws Exception {
        XObject object = Xlangc.compile("""
            let answer = 40;
            fn main() -> int {
                answer += 2;
                return answer;
            }
            """).object();
        XExecutable executable = Xld.link(List.of(object)).executable();
        XMachine machine = new XMachine();
        executable.loadInto(machine);
        machine.run();
        assertEquals(42, machine.cpu().register(0));
    }

    @Test
    void startupInitializesAndReadsGlobalAggregates() throws Exception {
        var compiled = Xlangc.compile("""
            struct Pair { ready: bool; value: int; }
            let pair = Pair { ready: true, value: 42 };
            fn main() -> int {
                if (pair.ready) { return pair.value; }
                return 0;
            }
            """);
        XExecutable executable = Xld.link(List.of(compiled.object())).executable();
        XMachine machine = new XMachine();
        executable.loadInto(machine);
        machine.run();
        assertEquals(42, machine.cpu().register(0));
    }

    @Test
    void resolvesGlobalCallsAcrossObjectsAndRunsStartup() throws Exception {
        byte[] mainText = new Assembler().opcode(Opcode.CALL).i32(0).ret().bytes();
        XObject main = object(mainText, new byte[] {1},
            List.of(symbol("main", XObject.Section.TEXT, 0, mainText.length, true)),
            List.of(relocation(XObject.Section.TEXT, 1, XObject.RelocationType.ABS32, "helper", 0)));
        byte[] helperText = new Assembler().movi(0, 42).ret().bytes();
        XObject helper = object(helperText, new byte[8],
            List.of(symbol("helper", XObject.Section.TEXT, 0, helperText.length, true),
                symbol("counter", XObject.Section.DATA, 0, 8, true)), List.of());

        XExecutable executable = Xld.link(List.of(main, helper)).executable();
        assertEquals(6L, executable.symbols().get("main"));
        assertEquals(12L, executable.symbols().get("helper"));
        assertEquals((long) executable.dataAddress() + 8, executable.symbols().get("counter"));

        XMachine machine = new XMachine();
        executable.loadInto(machine);
        machine.run();
        assertEquals(42, machine.cpu().register(0));
    }

    @Test
    void appliesAbs64ToWritablePageSeparatedData() throws Exception {
        Assembler assembler = new Assembler();
        assembler.movi(4, 0).movi(5, 42).memory(Opcode.STORE64, 5, 4)
            .memory(Opcode.LOAD64, 0, 4).ret();
        byte[] text = assembler.bytes();
        XObject object = object(text, new byte[8],
            List.of(symbol("main", XObject.Section.TEXT, 0, text.length, true),
                symbol("counter", XObject.Section.DATA, 0, 8, true)),
            List.of(relocation(XObject.Section.TEXT, 2, XObject.RelocationType.ABS64, "counter", 0)));

        XExecutable executable = Xld.link(List.of(object)).executable();
        assertEquals(0, executable.dataAddress() % 256);
        XMachine machine = new XMachine();
        executable.loadInto(machine);
        machine.run();
        assertEquals(42, machine.cpu().register(0));
        assertEquals(42, machine.readLong(executable.dataAddress()));
        assertTrue(machine.os().regions().stream().anyMatch(region -> region.name().equals("data")
            && region.protections().equals(EnumSet.of(Protection.READ, Protection.WRITE))));
    }

    @Test
    void localSymbolsMayRepeatAndResolveWithinTheirObject() throws Exception {
        byte[] firstText = new Assembler().jump(Opcode.JMP, 0).ret().bytes();
        XObject first = object(firstText, new byte[0],
            List.of(symbol("main", XObject.Section.TEXT, 0, firstText.length, true),
                symbol("$same", XObject.Section.TEXT, 5, 1, false)),
            List.of(relocation(XObject.Section.TEXT, 1, XObject.RelocationType.ABS32, "$same", 0)));
        byte[] secondText = new Assembler().nop().ret().bytes();
        XObject second = object(secondText, new byte[0],
            List.of(symbol("helper", XObject.Section.TEXT, 0, secondText.length, true),
                symbol("$same", XObject.Section.TEXT, 1, 1, false)), List.of());

        XExecutable executable = Xld.link(List.of(first, second)).executable();
        assertEquals(11, readI32(executable.text(), 7));
        XMachine machine = new XMachine();
        executable.loadInto(machine);
        machine.run();
    }

    @Test
    void verboseTraceShowsEveryPatchedByte() throws Exception {
        byte[] text = new Assembler().ret().bytes();
        XObject object = object(text, new byte[0],
            List.of(symbol("main", XObject.Section.TEXT, 0, 1, true)), List.of());
        Xld.LinkResult result = Xld.link(List.of(object), "main", true);
        assertTrue(result.trace().get(0).contains("startup -> main"));
        assertEquals(4, result.trace().stream().filter(line -> line.contains("byte [")).count());
        assertTrue(result.trace().stream().anyMatch(line -> line.contains("00 -> 06")));
    }

    @Test
    void reportsUndefinedDuplicateAndInvalidRelocations() {
        byte[] text = new Assembler().ret().bytes();
        XObject main = object(text, new byte[0],
            List.of(symbol("main", XObject.Section.TEXT, 0, 1, true)), List.of());
        XObject duplicate = object(text, new byte[0],
            List.of(symbol("main", XObject.Section.TEXT, 0, 1, true)), List.of());
        LinkException duplicateError = assertThrows(LinkException.class,
            () -> Xld.link(List.of(main, duplicate)));
        assertTrue(duplicateError.getMessage().contains("duplicate global symbol 'main'"));

        XObject undefined = object(new byte[5], new byte[0],
            List.of(symbol("main", XObject.Section.TEXT, 0, 5, true)),
            List.of(relocation(XObject.Section.TEXT, 1, XObject.RelocationType.ABS32, "missing", 0)));
        LinkException undefinedError = assertThrows(LinkException.class,
            () -> Xld.link(List.of(undefined)));
        assertTrue(undefinedError.getMessage().contains("undefined symbol 'missing'"));

        XObject badOffset = object(text, new byte[0],
            List.of(symbol("main", XObject.Section.TEXT, 0, 1, true)),
            List.of(relocation(XObject.Section.TEXT, 0, XObject.RelocationType.ABS64, "main", 0)));
        LinkException offsetError = assertThrows(LinkException.class,
            () -> Xld.link(List.of(badOffset)));
        assertTrue(offsetError.getMessage().contains("out-of-range ABS64 relocation"));
    }

    @Test
    void executableFormatRoundTripsAndRejectsBadMagic(@TempDir Path directory) throws Exception {
        byte[] text = new Assembler().ret().bytes();
        XExecutable original = Xld.link(List.of(object(text, new byte[] {7},
            List.of(symbol("main", XObject.Section.TEXT, 0, 1, true)), List.of()))).executable();
        Path executablePath = directory.resolve("program.xex");
        XExecutableIO.write(executablePath, original);
        XExecutable restored = XExecutableIO.read(executablePath);
        assertEquals(original.entryPoint(), restored.entryPoint());
        assertEquals(original.dataAddress(), restored.dataAddress());
        assertArrayEquals(original.text(), restored.text());
        assertArrayEquals(original.data(), restored.data());
        assertEquals(original.symbols(), restored.symbols());

        Path corrupt = directory.resolve("corrupt.xex");
        Files.write(corrupt, new byte[] {'N', 'O', 'P', 'E'});
        assertTrue(assertThrows(java.io.IOException.class,
            () -> XExecutableIO.read(corrupt)).getMessage().contains("not an XE01"));
    }

    private static XObject object(byte[] text, byte[] data, List<XObject.Symbol> symbols,
                                  List<XObject.Relocation> relocations) {
        return new XObject(text, data, symbols, relocations);
    }

    private static XObject.Symbol symbol(String name, XObject.Section section, int offset,
                                         int size, boolean global) {
        return new XObject.Symbol(name, section, offset, size, global);
    }

    private static XObject.Relocation relocation(XObject.Section section, int offset,
                                                 XObject.RelocationType type, String symbol,
                                                 long addend) {
        return new XObject.Relocation(section, offset, type, symbol, addend);
    }

    private static int readI32(byte[] bytes, int offset) {
        return bytes[offset] & 0xff | (bytes[offset + 1] & 0xff) << 8
            | (bytes[offset + 2] & 0xff) << 16 | (bytes[offset + 3] & 0xff) << 24;
    }
}
