package com.xlang.cli;

import com.xlang.compiler.Xlangc;
import com.xlang.compiler.print.AstPrinter;
import com.xlang.compiler.xir.XirPrinter;
import com.xlang.vm.HexProgram;
import com.xlang.vm.MachineFault;
import com.xlang.vm.XCpu;
import com.xlang.vm.XMachine;
import com.xlang.vm.Protection;
import java.util.EnumSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public static final String VERSION = "0.1.0-P5";

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
                System.out.println("Current phase: P5 (XOS virtual memory + syscalls)");
                System.out.println("Next milestone: P6 xlangc backend to XMachine ISA");
                yield 0;
            }
            case "tokens" -> frontEnd(rest, false);
            case "parse" -> frontEnd(rest, true);
            case "check" -> check(rest);
            case "ir" -> ir(rest);
            case "run" -> machine(rest, false);
            case "trace" -> machine(rest, true);
            case "mem" -> memory(rest);
            case "compile", "link", "layout", "syscall-trace" ->
                stub(cmd);
            default -> {
                System.err.println("xlang: unknown command '" + cmd + "'");
                printUsage(System.err);
                yield 2;
            }
        };
    }

    private static int memory(String[] args) {
        if (args.length == 0 || (!args[0].equals("show") && !args[0].equals("map"))) {
            System.err.println("Usage: xlang mem show | mem map <bytes> [rwx]");
            return 64;
        }
        try {
            XMachine machine = new XMachine();
            machine.load(new byte[] {0});
            if (args[0].equals("show")) {
                if (args.length != 1) throw new IllegalArgumentException("mem show takes no arguments");
            } else {
                if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("mem map requires <bytes> [rwx]");
                int length = Integer.decode(args[1]);
                EnumSet<Protection> protection = Protection.parse(args.length == 3 ? args[2] : "rw");
                int address = machine.os().mmap(length, protection, "cli-mmap");
                System.out.println("mapped " + length + " bytes at 0x" + String.format("%08x", address));
            }
            System.out.print(machine.os().describeMemory());
            return 0;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            System.err.println("xlang: " + ex.getMessage());
            return 65;
        }
    }

    private static int machine(String[] args, boolean trace) {
        if (args.length == 0) {
            System.err.println("Usage: xlang " + (trace ? "trace" : "run") + " <hex-program>");
            return 64;
        }
        try {
            byte[] program = HexProgram.parse(String.join(" ", args));
            XMachine machine = new XMachine();
            machine.load(program);
            var result = machine.run(XMachine.DEFAULT_STEP_LIMIT, trace);
            if (trace) result.trace().forEach(entry -> System.out.println(entry.format()));
            System.out.print(result.output());
            XCpu.Snapshot cpu = result.cpu();
            System.out.println("halted after " + cpu.steps() + " instructions");
            for (int i = 0; i < XCpu.REGISTER_COUNT; i++) {
                System.out.println("r" + i + " = " + cpu.register(i));
            }
            System.out.println("pc = " + cpu.pc() + ", Z = " + (cpu.zero() ? 1 : 0)
                + ", N = " + (cpu.negative() ? 1 : 0));
            return 0;
        } catch (IllegalArgumentException | MachineFault ex) {
            System.err.println("xlang: " + ex.getMessage());
            return 65;
        }
    }

    private static int ir(String[] args) {
        if (args.length != 1) { System.err.println("Usage: xlang ir <file>"); return 64; }
        final String source;
        try { source = Files.readString(Path.of(args[0])); }
        catch (IOException ex) { System.err.println("xlang: cannot read '" + args[0] + "': " + ex.getMessage()); return 66; }
        var result = Xlangc.lower(source);
        result.diagnostics().forEach(d -> System.err.println(d.format(args[0])));
        if (result.hasErrors()) return 1;
        System.out.print(XirPrinter.print(result.module()));
        return 0;
    }

    private static int check(String[] args) {
        if (args.length != 1) { System.err.println("Usage: xlang check <file>"); return 64; }
        final String source;
        try { source = Files.readString(Path.of(args[0])); }
        catch (IOException ex) { System.err.println("xlang: cannot read '" + args[0] + "': " + ex.getMessage()); return 66; }
        var result = Xlangc.check(source);
        result.diagnostics().forEach(d -> System.err.println(d.format(args[0])));
        if (result.hasErrors()) return 1;
        System.out.println(args[0] + ": type check passed");
        return 0;
    }

    private static int frontEnd(String[] args, boolean parse) {
        if (args.length != 1) { System.err.println("Usage: xlang " + (parse ? "parse" : "tokens") + " <file>"); return 64; }
        final String source;
        try { source = Files.readString(Path.of(args[0])); }
        catch (IOException ex) { System.err.println("xlang: cannot read '" + args[0] + "': " + ex.getMessage()); return 66; }
        if (!parse) {
            var result = Xlangc.lex(source);
            result.tokens().stream().filter(t -> t.type() != com.xlang.compiler.token.TokenType.EOF).forEach(System.out::println);
            result.diagnostics().forEach(d -> System.err.println(d.format(args[0])));
            return result.hasErrors() ? 1 : 0;
        }
        var result = Xlangc.parse(source);
        result.diagnostics().forEach(d -> System.err.println(d.format(args[0])));
        if (result.hasErrors()) return 1;
        System.out.print(AstPrinter.print(result.program())); return 0;
    }

    private static int stub(String cmd) {
        Phase requiredPhase = plannedPhaseFor(cmd);
        System.err.println(
            "xlang " + cmd + ": not implemented yet in P5. "
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
            "  tokens <file>         Print the P1 token stream",
            "  parse <file>          Parse source and print its AST",
            "  check <file>          Run lexer, parser, and P2 type checker",
            "  ir <file>             Lower checked source to P3 three-address XIR",
            "  compile <file>        [P6] Compile .xl source to a .xo object",
            "  link <files>          [P7] Link .xo objects into a .xex executable",
            "  run <hex-program>     Run hand-assembled bytes on the P4 XMachine",
            "  trace <hex-program>   Same as run, but log every instruction",
            "  mem show              Show the P5 process page table",
            "  mem map <bytes> [rwx] Add and display an anonymous mapping",
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
