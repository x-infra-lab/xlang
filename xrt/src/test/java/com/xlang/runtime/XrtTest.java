package com.xlang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.Xlangc;
import com.xlang.compiler.xir.Xir;
import com.xlang.linker.Xld;
import com.xlang.vm.XMachine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class XrtTest {
    @Test
    void runtimeIsXlangSourceAndExportsTheLibcAbi() {
        assertTrue(Xrt.source().contains("fn malloc("));
        assertTrue(Xrt.source().contains("fn printf("));
        var object = Xrt.object();
        for (String name : List.of("start", "write", "exit", "malloc", "free", "printf")) {
            assertTrue(object.symbols().stream().anyMatch(symbol -> symbol.global()
                && symbol.name().equals(name)), "missing runtime symbol " + name);
        }
    }

    @Test
    void runtimePrintsAllocatesReusesAndExits() throws Exception {
        var application = Xlangc.compile("""
            fn main() -> int {
                printf("answer=%d %%\\n", 42);
                printf("negative=%d\\n", -7);
                printf("zero=%d\\n", 0);
                let first = malloc(16);
                free(first);
                let second = malloc(8);
                if (first != second) { return 99; }
                free(second);
                return 7;
            }
            """);
        assertFalse(application.hasErrors(), application.diagnostics().toString());
        var executable = Xld.link(List.of(Xrt.object(), application.object()),
            Xrt.ENTRY_SYMBOL, false).executable();
        XMachine machine = new XMachine();
        executable.loadInto(machine);
        machine.run();

        assertEquals("answer=42 %\nnegative=-7\nzero=0\n", machine.os().outputText());
        assertTrue(machine.os().exited());
        assertEquals(7, machine.os().exitCode());
        assertTrue(machine.os().syscallEvents().stream().anyMatch(event -> event.name().equals("brk")));
        assertEquals("exit", machine.os().syscallEvents().get(machine.os().syscallEvents().size() - 1).name());
    }

    @Test
    void capstoneExampleExercisesTheCompleteToolchain() throws Exception {
        String source = Files.readString(repositoryRoot().resolve("examples/capstone.xl"));

        var lexed = Xlangc.lex(source);
        assertFalse(lexed.hasErrors(), lexed.diagnostics().toString());
        assertTrue(lexed.tokens().stream().anyMatch(token -> token.lexeme().equals("struct")));
        assertTrue(lexed.tokens().stream().anyMatch(token -> token.lexeme().equals("union")));

        var parsed = Xlangc.parse(source);
        assertFalse(parsed.hasErrors(), parsed.diagnostics().toString());

        var checked = Xlangc.check(source);
        assertFalse(checked.hasErrors(), checked.diagnostics().toString());
        assertTrue(checked.typeCheck().aggregates().containsKey("Report"));
        assertTrue(checked.typeCheck().aggregates().containsKey("Reading"));

        var lowered = Xlangc.lower(source);
        assertFalse(lowered.hasErrors(), lowered.diagnostics().toString());
        assertTrue(lowered.module().functions().stream()
            .flatMap(function -> function.blocks().stream())
            .flatMap(block -> block.instructions().stream())
            .anyMatch(instruction -> instruction instanceof Xir.PointerOffset));

        var application = Xlangc.compile(source);
        assertFalse(application.hasErrors(), application.diagnostics().toString());
        assertTrue(application.object().symbols().stream()
            .anyMatch(symbol -> symbol.name().equals("main")));

        var link = Xld.link(List.of(Xrt.object(), application.object()),
            Xrt.ENTRY_SYMBOL, true);
        assertFalse(link.trace().isEmpty());

        XMachine machine = new XMachine();
        link.executable().loadInto(machine);
        machine.run();

        assertEquals("xlang capstone total=42\nReport layout bytes=48\n",
            machine.os().outputText());
        assertTrue(machine.os().exited());
        assertEquals(0, machine.os().exitCode());
        assertTrue(machine.os().syscallEvents().stream()
            .anyMatch(event -> event.name().equals("write")));
        assertTrue(machine.os().syscallEvents().stream()
            .anyMatch(event -> event.name().equals("brk")));
        assertEquals("exit", machine.os().syscallEvents()
            .get(machine.os().syscallEvents().size() - 1).name());
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("cannot locate the xlang repository root");
        }
        return current;
    }
}
