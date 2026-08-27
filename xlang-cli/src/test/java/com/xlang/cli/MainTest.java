package com.xlang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void phaseCommandReportsP4() {
        Capture cap = Capture.run(() -> Main.run(new String[] {"phase"}));
        assertEquals(0, cap.exit);
        assertTrue(cap.stdout.contains("P4"));
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
        Capture cap = Capture.run(() -> Main.run(new String[] {"compile", "foo.xl"}));
        assertNotEquals(0, cap.exit);
        assertTrue(cap.stderr.contains("P6"),
            "compile stub should announce it is planned for P6, was: " + cap.stderr);
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
