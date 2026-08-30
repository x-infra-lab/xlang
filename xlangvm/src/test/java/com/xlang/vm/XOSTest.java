package com.xlang.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class XOSTest {
    @Test void bootCreatesProtectedCodeAndStackMappings() {
        XMachine machine = machine(new Assembler().halt().bytes());
        XOS os = machine.os();
        assertEquals(0, os.translate(0, Access.EXECUTE, 0));
        assertThrows(PageFault.class, () -> os.translate(0, Access.WRITE, 0));
        assertThrows(PageFault.class, () -> os.translate(XOS.HEAP_BASE, Access.READ, 0));
        String map = os.describeMemory();
        assertTrue(map.contains("r-x"));
        assertTrue(map.contains("code"));
        assertTrue(map.contains("rw-"));
        assertTrue(map.contains("stack"));
    }

    @Test void brkGrowsShrinksAndSupportsCrossPageValues() {
        XOS os = machine(new Assembler().halt().bytes()).os();
        int address = XOS.HEAP_BASE + PageTable.PAGE_SIZE - 4;
        os.brk(address + 8);
        os.writeLong(address, 0x1122334455667788L, 0);
        assertEquals(0x1122334455667788L, os.readLong(address, 0));
        assertEquals(address + 8, os.currentBreak());
        os.brk(XOS.HEAP_BASE);
        assertThrows(PageFault.class, () -> os.readLong(address, 0));
    }

    @Test void mmapAndMprotectEnforcePermissionBits() {
        XOS os = machine(new Assembler().halt().bytes()).os();
        int address = os.mmap(10, EnumSet.of(Protection.READ), "fixture");
        assertThrows(PageFault.class, () -> os.writeLong(address, 7, 0));
        os.mprotect(address, PageTable.PAGE_SIZE, EnumSet.of(Protection.READ, Protection.WRITE));
        os.writeLong(address, 7, 0);
        assertEquals(7, os.readLong(address, 0));
        assertThrows(PageFault.class, () -> os.translate(address, Access.EXECUTE, 0));
        assertTrue(os.describeMemory().contains("fixture"));
    }

    @Test void writeSyscallReadsGuestMemoryAndReturnsByteCount() {
        byte[] message = "hello\n".getBytes(StandardCharsets.UTF_8);
        byte[] program = new Assembler()
            .movi(0, XOS.SYS_WRITE).movi(1, 1).movi(2, XOS.HEAP_BASE).movi(3, message.length)
            .syscall().halt().bytes();
        XMachine machine = machine(program);
        machine.os().brk(XOS.HEAP_BASE + message.length);
        machine.os().writeKernel(XOS.HEAP_BASE, message);
        var result = machine.run();
        assertEquals("hello\n", result.output());
        assertEquals(message.length, result.cpu().register(0));
    }

    @Test void syscallAndFrameFailuresAreReported() {
        XMachine unknown = machine(new Assembler().movi(0, 99).syscall().halt().bytes());
        assertTrue(assertThrows(MachineFault.class, unknown::run).getMessage().contains("unknown syscall"));

        XOS os = new XMachine(4096).os();
        os.boot(new Assembler().halt().bytes());
        assertThrows(IllegalStateException.class,
            () -> os.mmap(4096, EnumSet.of(Protection.READ), "too-large"));
    }

    @Test void brkAndExitSyscallsAreLoggedAndExitStopsTheCpu() {
        byte[] program = new Assembler()
            .movi(0, XOS.SYS_BRK).movi(1, XOS.HEAP_BASE + 64).syscall()
            .movi(0, XOS.SYS_EXIT).movi(1, 7).syscall()
            .movi(0, 99).halt().bytes();
        XMachine machine = machine(program);
        machine.run();
        assertEquals(XOS.HEAP_BASE + 64, machine.os().currentBreak());
        assertTrue(machine.os().exited());
        assertEquals(7, machine.os().exitCode());
        assertEquals(2, machine.os().syscallEvents().size());
        assertTrue(machine.os().syscallEvents().get(0).format().contains("brk(address="));
        assertEquals("exit(status=7) = 0", machine.os().syscallEvents().get(1).format());
    }

    private static XMachine machine(byte[] program) {
        XMachine machine = new XMachine(4096);
        machine.load(program);
        return machine;
    }
}
