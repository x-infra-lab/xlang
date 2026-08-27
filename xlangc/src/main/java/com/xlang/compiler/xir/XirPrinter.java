package com.xlang.compiler.xir;

import java.util.stream.Collectors;

/** Deterministic textual form of XIR for the CLI, tests, and later phases. */
public final class XirPrinter {
    private XirPrinter() {}

    public static String print(Xir.Module module) {
        StringBuilder out = new StringBuilder("module {\n");
        for (Xir.Global global : module.globals()) {
            out.append("  global ").append(global.name()).append(": ")
                .append(global.type().displayName()).append('\n');
        }
        if (!module.globals().isEmpty() && !module.functions().isEmpty()) out.append('\n');
        for (int i = 0; i < module.functions().size(); i++) {
            function(out, module.functions().get(i));
            if (i + 1 < module.functions().size()) out.append('\n');
        }
        return out.append("}\n").toString();
    }

    private static void function(StringBuilder out, Xir.Function function) {
        String parameters = function.parameters().stream()
            .map(v -> v.name() + ": " + v.type().displayName())
            .collect(Collectors.joining(", "));
        out.append("  fn ").append(function.name()).append('(').append(parameters)
            .append(") -> ").append(function.returnType().displayName()).append(" {\n");
        for (Xir.BasicBlock block : function.blocks()) {
            out.append("    ").append(block.label()).append(":\n");
            for (Xir.Instruction instruction : block.instructions()) {
                out.append("      ").append(instruction(instruction)).append('\n');
            }
            out.append("      ").append(terminator(block.terminator())).append('\n');
        }
        out.append("  }\n");
    }

    private static String instruction(Xir.Instruction instruction) {
        if (instruction instanceof Xir.Const constant) {
            return constant.result().name() + " = const " + literal(constant.value())
                + ": " + constant.result().type().displayName();
        }
        if (instruction instanceof Xir.Copy copy) {
            return copy.target().name() + " = copy " + copy.source().name();
        }
        if (instruction instanceof Xir.Unary unary) {
            return unary.result().name() + " = " + operator(unary.operator()) + " " + unary.operand().name();
        }
        if (instruction instanceof Xir.Binary binary) {
            return binary.result().name() + " = " + operator(binary.operator()) + " "
                + binary.left().name() + ", " + binary.right().name();
        }
        Xir.Call call = (Xir.Call) instruction;
        String args = call.arguments().stream().map(Xir.Value::name).collect(Collectors.joining(", "));
        String prefix = call.result() == null ? "" : call.result().name() + " = ";
        return prefix + "call @" + call.function() + "(" + args + ")";
    }

    private static String terminator(Xir.Terminator terminator) {
        if (terminator instanceof Xir.Jump jump) return "jump " + jump.target();
        if (terminator instanceof Xir.Branch branch) {
            return "branch " + branch.condition().name() + ", " + branch.whenTrue() + ", " + branch.whenFalse();
        }
        if (terminator instanceof Xir.Return returned) {
            return returned.value() == null ? "return" : "return " + returned.value().name();
        }
        return "unreachable";
    }

    private static String operator(com.xlang.compiler.token.TokenType operator) {
        return operator.name().toLowerCase();
    }

    private static String literal(Object value) {
        if (!(value instanceof String text)) return String.valueOf(value);
        return '"' + text.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t").replace("\0", "\\0")
            .replace("\"", "\\\"") + '"';
    }
}
