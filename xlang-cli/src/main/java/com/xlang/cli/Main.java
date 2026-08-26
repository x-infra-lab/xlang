package com.xlang.cli;

import java.util.Arrays;
import java.util.List;

/**
 * Single entry point for the xlang toolchain.
 *
 * <p>Design note: the whole project is a staged tutorial. Every subcommand you
 * see here maps to one phase in {@code docs/phases}. In P0 most subcommands
 * are deliberate stubs that print which phase will implement them, so that as
 * we advance we can flip stubs to real logic without shuffling the CLI.
 */
public final class Main {

    /** Semantic version of the xlang toolchain. Kept in one place on purpose. */
    public static final String VERSION = "0.1.0-P0";

    private Main() {}

    public static void main(String[] args) {
        int exit = run(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    /** Testable entry point: returns an exit code instead of calling System.exit. */
    public static int run(String[] args) {
        if (args.length == 0) {
            printUsage(System.out);
            return 0;
        }
        String cmd = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        return switch (cmd) {
            case "--version", "-V", "version" -> {
                System.out.println("xlang " + VERSION);
                yield 0;
            }
            case "--help", "-h", "help" -> {
                printUsage(System.out);
                yield 0;
            }
            case "phase" -> {
                System.out.println("Current phase: P0 (scaffold)");
                System.out.println("Next milestone: P1 hand-written lexer + parser");
                yield 0;
            }
            case "compile", "run", "link", "trace", "mem", "layout", "syscall-trace" ->
                stub(cmd);
            default -> {
                System.err.println("xlang: unknown command '" + cmd + "'");
                printUsage(System.err);
                yield 2;
            }
        };
    }

    private static int stub(String cmd) {
        Phase requiredPhase = plannedPhaseFor(cmd);
        System.err.println(
            "xlang " + cmd + ": not implemented yet in P0. "
            + "Planned for " + requiredPhase.id() + " -- " + requiredPhase.title() + ".");
        return 64; // EX_USAGE-ish: the command is real, just not wired yet.
    }

    private static Phase plannedPhaseFor(String cmd) {
        return switch (cmd) {
            case "compile" -> new Phase("P6", "xlangc backend: XIR -> .xo");
            case "run"     -> new Phase("P4", "XMachine + XCPU boot");
            case "link"    -> new Phase("P7", "xld linker with relocation");
            case "trace"   -> new Phase("P4", "instruction-level tracer");
            case "mem"     -> new Phase("P5", "XOS memory subsystem");
            case "layout"  -> new Phase("P9", "struct layout visualiser");
            case "syscall-trace" -> new Phase("P8", "mini libc + syscall log");
            default -> new Phase("?", "unknown");
        };
    }

    private static void printUsage(java.io.PrintStream out) {
        List<String> lines = List.of(
            "xlang " + VERSION + " -- a staged C-principles teaching toolchain",
            "",
            "Usage: xlang <command> [args]",
            "",
            "Commands:",
            "  version               Print xlang version",
            "  help                  Print this help",
            "  phase                 Print current implementation phase",
            "  compile <file>        [P6] Compile .xl source to a .xo object",
            "  link <files>          [P7] Link .xo objects into a .xex executable",
            "  run <file>            [P4] Run a program on the XMachine",
            "  trace <file>          [P4] Same as run, but log every instruction",
            "  mem <subcmd>          [P5] Inspect virtual memory / page table / heap",
            "  layout <type>         [P9] Print struct/union memory layout",
            "  syscall-trace <file>  [P8] strace-style syscall log",
            "",
            "See docs/phases for what each phase actually delivers."
        );
        for (String line : lines) out.println(line);
    }

    /** A phase reference used only to report "planned for" info in stubs. */
    public record Phase(String id, String title) {}
}
