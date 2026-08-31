package com.xlang.compiler.xir;

import com.xlang.compiler.sema.Type;
import com.xlang.compiler.token.TokenType;
import java.util.List;

/** Three-address intermediate representation produced by P3. */
public final class Xir {
    private Xir() {}

    public record Module(List<Global> globals, List<Function> functions) {
        public Module { globals = List.copyOf(globals); functions = List.copyOf(functions); }
    }

    public record Global(String name, Type type) {}

    public record Function(String name, List<Value> parameters, Type returnType,
                           List<BasicBlock> blocks) {
        public Function { parameters = List.copyOf(parameters); blocks = List.copyOf(blocks); }
    }

    public record BasicBlock(String label, List<Instruction> instructions, Terminator terminator) {
        public BasicBlock { instructions = List.copyOf(instructions); }
    }

    /** Named virtual value. Globals start with @; locals and temporaries with %. */
    public record Value(String name, Type type) {}

    public sealed interface Instruction permits Const, Copy, Unary, Binary, Call, Allocate,
        GlobalAddress, AddressOf, PointerOffset, Load, Store, MemCopy {}
    public record Const(Value result, Object value) implements Instruction {}
    public record Copy(Value target, Value source) implements Instruction {}
    public record Unary(Value result, TokenType operator, Value operand) implements Instruction {}
    public record Binary(Value result, TokenType operator, Value left, Value right) implements Instruction {}
    public record Call(Value result, String function, List<Value> arguments) implements Instruction {
        public Call { arguments = List.copyOf(arguments); }
    }
    public record Allocate(Value result, Type allocatedType) implements Instruction {}
    public record GlobalAddress(Value result, String symbol) implements Instruction {}
    public record AddressOf(Value result, Value target) implements Instruction {}
    public record PointerOffset(Value result, Value base, Value byteOffset) implements Instruction {}
    public record Load(Value result, Value address) implements Instruction {}
    public record Store(Value address, Value source) implements Instruction {}
    public record MemCopy(Value targetAddress, Value sourceAddress, long size) implements Instruction {}

    public sealed interface Terminator permits Jump, Branch, Return, Unreachable {}
    public record Jump(String target) implements Terminator {}
    public record Branch(Value condition, String whenTrue, String whenFalse) implements Terminator {}
    public record Return(Value value) implements Terminator {}
    public record Unreachable() implements Terminator {}
}
