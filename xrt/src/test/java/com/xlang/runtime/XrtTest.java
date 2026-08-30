package com.xlang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xlang.compiler.Xlangc;
import com.xlang.linker.Xld;
import com.xlang.vm.XMachine;
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
}
