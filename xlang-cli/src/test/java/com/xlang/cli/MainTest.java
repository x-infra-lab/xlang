package com.xlang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import com.xlang.vm.Assembler;
import com.xlang.vm.HexProgram;
import com.xlang.vm.XOS;
import com.xlang.compiler.object.XObjectIO;
import com.xlang.linker.XExecutableIO;
import com.xlang.vm.XMachine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI smoke tests, including the P1 front-end commands. */
class MainTest {

    @Test
    void versionPrintsAndSucceeds() {
        Capture cap = Capture.run(() -> Main.run(new String[] {"--version"}));
        assertEquals(0, cap.exit);
        assertTrue(cap.stdout.contains(Main.VERSION),
            "version output should contain " + Main.VERSION + ", was: " + cap.stdout);
    }

    @Test
    void helpMentionsAllPlannedCommands() {
        Capture cap = Capture.run(() -> Main.run(new String[] {"help"}));
        assertEquals(0, cap.exit);
        for (String cmd : new String[] {"compile", "link", "run", "trace", "mem", "layout"}) {
            assertTrue(cap.stdout.contains(cmd),
                "help should mention " + cmd + ", was: " + cap.stdout);
        }
    }

    @Test
    void phaseCommandReportsP8() {
        Capture cap = Capture.run(() -> Main.run(new String[] {"phase"}));
        assertEquals(0, cap.exit);
        assertTrue(cap.stdout.contains("P8"));
    }

    @Test
    void checkCommandReportsSuccessAndTypeErrors(@TempDir Path directory) throws Exception {
        Path valid = directory.resolve("valid.xl");
        Files.writeString(valid, "fn main() -> int { return 0; }");
        Capture ok = Capture.run(() -> Main.run(new String[] {"check", valid.toString()}));
        assertEquals(0, ok.exit);
        assertTrue(ok.stdout.contains("type check passed"));

        Path invalid = directory.resolve("invalid.xl");
        Files.writeString(invalid, "fn main() -> int { return true; }");
        Capture bad = Capture.run(() -> Main.run(new String[] {"check", invalid.toString()}));
        assertNotEquals(0, bad.exit);
        assertTrue(bad.stderr.contains("expects int but got bool"));
    }

    @Test
    void irCommandPrintsXirAndRejectsInvalidSource(@TempDir Path directory) throws Exception {
        Path valid = directory.resolve("valid.xl");
        Files.writeString(valid, "fn main() -> int { return 1 + 2; }");
        Capture ok = Capture.run(() -> Main.run(new String[] {"ir", valid.toString()}));
        assertEquals(0, ok.exit);
        assertTrue(ok.stdout.contains("module {"));
        assertTrue(ok.stdout.contains("plus"));

        Path invalid = directory.resolve("invalid.xl");
        Files.writeString(invalid, "fn main() -> int { return false; }");
        Capture bad = Capture.run(() -> Main.run(new String[] {"ir", invalid.toString()}));
        assertNotEquals(0, bad.exit);
        assertTrue(bad.stderr.contains("expects int but got bool"));
    }

    @Test
    void runAndTraceExecuteHexPrograms() {
        String program = "10 00 2a 00 00 00 00 00 00 00 00";
        Capture run = Capture.run(() -> Main.run(new String[] {"run", program}));
        assertEquals(0, run.exit);
        assertTrue(run.stdout.contains("r0 = 42"));
        assertTrue(run.stdout.contains("halted after 2 instructions"));

        Capture trace = Capture.run(() -> Main.run(new String[] {"trace", program}));
        assertEquals(0, trace.exit);
        assertTrue(trace.stdout.contains("movi r0, 42"));
        assertTrue(trace.stdout.contains("000a:"));

        Capture invalid = Capture.run(() -> Main.run(new String[] {"run", "zz"}));
        assertNotEquals(0, invalid.exit);
        assertTrue(invalid.stderr.contains("invalid hex digit"));
    }

    @Test
    void writeSyscallProducesOutputAndMemCommandsVisualizeMappings() {
        byte[] message = "Hi\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new Assembler()
            .movi(0, XOS.SYS_WRITE).movi(1, 1).movi(2, 42).movi(3, message.length)
            .syscall().halt().raw(message).bytes();
        Capture run = Capture.run(() -> Main.run(new String[] {"run", HexProgram.format(bytes)}));
        assertEquals(0, run.exit);
        assertTrue(run.stdout.contains("Hi\n"));
        assertTrue(run.stdout.contains("r0 = 3"));

        Capture show = Capture.run(() -> Main.run(new String[] {"mem", "show"}));
        assertEquals(0, show.exit);
        assertTrue(show.stdout.contains("virtual range"));
        assertTrue(show.stdout.contains("code"));
        assertTrue(show.stdout.contains("stack"));

        Capture map = Capture.run(() -> Main.run(new String[] {"mem", "map", "512", "r--"}));
        assertEquals(0, map.exit);
        assertTrue(map.stdout.contains("mapped 512 bytes at 0x00040000"));
        assertTrue(map.stdout.contains("cli-mmap"));
        assertTrue(map.stdout.contains("r--"));
    }

    @Test
    void compileWritesReadableObjectAndRejectsInvalidSource(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("answer.xl");
        Path object = directory.resolve("custom.xo");
        Files.writeString(source, "fn main() -> int { return 40 + 2; }");
        Capture ok = Capture.run(() -> Main.run(new String[] {"compile", source.toString(), "-o", object.toString()}));
        assertEquals(0, ok.exit);
        assertTrue(ok.stdout.contains("wrote " + object));
        assertTrue(Files.exists(object));
        assertTrue(XObjectIO.read(object).symbols().stream().anyMatch(symbol -> symbol.name().equals("main")));

        Path invalid = directory.resolve("invalid.xl");
        Files.writeString(invalid, "fn main() -> int { return true; }");
        Capture bad = Capture.run(() -> Main.run(new String[] {"compile", invalid.toString()}));
        assertNotEquals(0, bad.exit);
        assertTrue(bad.stderr.contains("expects int but got bool"));
        assertFalse(Files.exists(directory.resolve("invalid.xo")));
    }

    @Test
    void linkWritesRunnableExecutableAndPrintsRelocations(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("answer.xl");
        Path object = directory.resolve("answer.xo");
        Path executablePath = directory.resolve("answer.xex");
        Files.writeString(source, "fn main() -> int { return 40 + 2; }");
        assertEquals(0, Capture.run(() -> Main.run(new String[] {
            "compile", source.toString(), "-o", object.toString()
        })).exit);

        Capture linked = Capture.run(() -> Main.run(new String[] {
            "link", object.toString(), "-o", executablePath.toString(), "--verbose"
        }));
        assertEquals(0, linked.exit);
        assertTrue(linked.stdout.contains("relocate"));
        assertTrue(linked.stdout.contains("byte ["));
        assertTrue(linked.stdout.contains("wrote " + executablePath));
        var executable = XExecutableIO.read(executablePath);
        XMachine machine = new XMachine();
        executable.loadInto(machine);
        machine.run();
        assertEquals(42, machine.cpu().register(0));
    }

    @Test
    void runtimeLinkAndSyscallTraceRunXrtProgram(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("runtime-demo.xl");
        Path object = directory.resolve("runtime-demo.xo");
        Path executable = directory.resolve("runtime-demo.xex");
        Files.writeString(source, """
            fn main() -> int {
                printf("value=%d\\n", 42);
                let memory = malloc(8);
                free(memory);
                return 7;
            }
            """);
        assertEquals(0, Capture.run(() -> Main.run(new String[] {
            "compile", source.toString(), "-o", object.toString()
        })).exit);
        Capture linked = Capture.run(() -> Main.run(new String[] {
            "link", object.toString(), "--runtime", "-o", executable.toString()
        }));
        assertEquals(0, linked.exit, linked.stderr);

        Capture traced = Capture.run(() -> Main.run(new String[] {
            "syscall-trace", executable.toString()
        }));
        assertEquals(0, traced.exit, traced.stderr);
        assertTrue(traced.stdout.contains("write(fd=1"));
        assertTrue(traced.stdout.contains("brk(address="));
        assertTrue(traced.stdout.contains("exit(status=7)"));
        assertTrue(traced.stdout.contains("process exited with status 7"));
    }

    @Test
    void helpMentionsFrontEndCommands() {
        Capture cap = Capture.run(() -> Main.run(new String[] {"help"}));
        assertTrue(cap.stdout.contains("tokens"));
        assertTrue(cap.stdout.contains("parse"));
        assertTrue(cap.stdout.contains("check"));
        assertTrue(cap.stdout.contains("ir"));
    }

    @Test
    void unknownCommandFails() {
        Capture cap = Capture.run(() -> Main.run(new String[] {"nope"}));
        assertNotEquals(0, cap.exit);
    }

    @Test
    void stubbedCommandsFailLoudlyButPointAtAPhase() {
        Capture cap = Capture.run(() -> Main.run(new String[] {"layout", "Thing"}));
        assertNotEquals(0, cap.exit);
        assertTrue(cap.stderr.contains("P9"),
            "stub should announce it is planned for P9, was: " + cap.stderr);
    }

    /** Tiny stdout/stderr capture helper so tests don't leak println noise. */
    private record Capture(int exit, String stdout, String stderr) {
        static Capture run(java.util.function.IntSupplier body) {
            PrintStream origOut = System.out;
            PrintStream origErr = System.err;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));
            try {
                int rc = body.getAsInt();
                return new Capture(rc, out.toString(), err.toString());
            } finally {
                System.setOut(origOut);
                System.setErr(origErr);
            }
        }
    }
}
