package com.xlang.compiler.backend;

import com.xlang.compiler.object.XObject;
import com.xlang.compiler.sema.Type;
import com.xlang.compiler.token.TokenType;
import com.xlang.compiler.xir.Xir;
import com.xlang.vm.Opcode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Naive, readable P6 backend from typed XIR to relocatable XMachine code. */
public final class XBackend {
    private static final int RETURN = 0;
    private static final int RIGHT = 3;
    private static final int ADDRESS = 4;
    private static final int LEFT = 5;
    private static final int FRAME_POINTER = 6;
    private static final int STACK_POINTER = 7;

    private final ByteArrayOutputStream text = new ByteArrayOutputStream();
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private final List<XObject.Symbol> symbols = new ArrayList<>();
    private final List<XObject.Relocation> relocations = new ArrayList<>();
    private final Map<String, String> strings = new LinkedHashMap<>();
    private int internalLabel;

    public XObject compile(Xir.Module module) {
        for (Xir.Global global : module.globals()) {
            alignData(8);
            symbols.add(new XObject.Symbol(global.name(), XObject.Section.DATA, data.size(), 8, true));
            writeDataLong(0);
        }
        for (Xir.Function function : module.functions()) compileFunction(function);
        return new XObject(text.toByteArray(), data.toByteArray(), symbols, relocations);
    }

    private void compileFunction(Xir.Function function) {
        Map<Xir.Value, Integer> slots = allocateSlots(function);
        symbols.add(new XObject.Symbol(function.name(), XObject.Section.TEXT, text.size(), 0,
            !function.name().startsWith("$")));
        emitPush(FRAME_POINTER);
        emitMov(FRAME_POINTER, STACK_POINTER);
        int frameSize = slots.size() * 8;
        if (frameSize > 0) {
            emitMovI(ADDRESS, frameSize);
            emitTri(Opcode.SUB, STACK_POINTER, STACK_POINTER, ADDRESS);
        }
        for (int index = 0; index < function.parameters().size(); index++) {
            emitMovI(ADDRESS, 16L + index * 8L);
            emitTri(Opcode.ADD, ADDRESS, FRAME_POINTER, ADDRESS);
            emitMemory(Opcode.LOAD64, LEFT, ADDRESS);
            store(function.parameters().get(index), LEFT, slots);
        }
        for (Xir.BasicBlock block : function.blocks()) {
            mark(blockLabel(function, block.label()));
            for (Xir.Instruction instruction : block.instructions()) compileInstruction(instruction, slots);
            compileTerminator(function, block.terminator(), slots);
        }
    }

    private void compileInstruction(Xir.Instruction instruction, Map<Xir.Value, Integer> slots) {
        if (instruction instanceof Xir.Const constant) {
            if (constant.value() instanceof String string) emitAddress(LEFT, stringSymbol(string));
            else emitMovI(LEFT, constant.value() instanceof Boolean bool ? (bool ? 1 : 0) : (Long) constant.value());
            store(constant.result(), LEFT, slots);
        } else if (instruction instanceof Xir.Copy copy) {
            load(copy.source(), LEFT, slots); store(copy.target(), LEFT, slots);
        } else if (instruction instanceof Xir.Unary unary) {
            load(unary.operand(), LEFT, slots);
            if (unary.operator() == TokenType.MINUS) {
                emitMovI(RIGHT, 0); emitTri(Opcode.SUB, LEFT, RIGHT, LEFT);
            } else if (unary.operator() == TokenType.BANG) {
                emitMovI(RIGHT, 0); emitCmp(LEFT, RIGHT); emitBoolean(TokenType.EQ_EQ);
            } else throw new IllegalStateException("unsupported checked unary operator " + unary.operator());
            store(unary.result(), LEFT, slots);
        } else if (instruction instanceof Xir.Binary binary) {
            load(binary.left(), LEFT, slots); load(binary.right(), RIGHT, slots);
            Opcode arithmetic = arithmetic(binary.operator());
            if (arithmetic != null) emitTri(arithmetic, LEFT, LEFT, RIGHT);
            else { emitCmp(LEFT, RIGHT); emitBoolean(binary.operator()); }
            store(binary.result(), LEFT, slots);
        } else if (instruction instanceof Xir.Call call) {
            for (int index = call.arguments().size() - 1; index >= 0; index--) {
                load(call.arguments().get(index), LEFT, slots); emitPush(LEFT);
            }
            emitCall(call.function());
            if (!call.arguments().isEmpty()) {
                emitMovI(ADDRESS, call.arguments().size() * 8L);
                emitTri(Opcode.ADD, STACK_POINTER, STACK_POINTER, ADDRESS);
            }
            if (call.result() != null) store(call.result(), RETURN, slots);
        }
    }

    private void compileTerminator(Xir.Function function, Xir.Terminator terminator,
                                   Map<Xir.Value, Integer> slots) {
        if (terminator instanceof Xir.Jump jump) {
            emitJump(Opcode.JMP, blockLabel(function, jump.target()));
        } else if (terminator instanceof Xir.Branch branch) {
            load(branch.condition(), LEFT, slots); emitMovI(RIGHT, 0); emitCmp(LEFT, RIGHT);
            emitJump(Opcode.JNZ, blockLabel(function, branch.whenTrue()));
            emitJump(Opcode.JMP, blockLabel(function, branch.whenFalse()));
        } else if (terminator instanceof Xir.Return returned) {
            if (returned.value() != null) load(returned.value(), RETURN, slots);
            emitMov(STACK_POINTER, FRAME_POINTER); emitPop(FRAME_POINTER); emitOpcode(Opcode.RET);
        } else if (terminator instanceof Xir.Unreachable) {
            emitOpcode(Opcode.HALT);
        }
    }

    private void emitBoolean(TokenType comparison) {
        String trueLabel = localLabel("bool.true");
        String falseLabel = localLabel("bool.false");
        String endLabel = localLabel("bool.end");
        switch (comparison) {
            case EQ_EQ -> emitJump(Opcode.JZ, trueLabel);
            case BANG_EQ -> emitJump(Opcode.JNZ, trueLabel);
            case LT -> emitJump(Opcode.JN, trueLabel);
            case LT_EQ -> { emitJump(Opcode.JN, trueLabel); emitJump(Opcode.JZ, trueLabel); }
            case GT -> { emitJump(Opcode.JN, falseLabel); emitJump(Opcode.JZ, falseLabel); }
            case GT_EQ -> emitJump(Opcode.JN, falseLabel);
            default -> throw new IllegalStateException("not a comparison " + comparison);
        }
        if (comparison == TokenType.GT || comparison == TokenType.GT_EQ) {
            emitMovI(LEFT, 1); emitJump(Opcode.JMP, endLabel);
            mark(falseLabel); emitMovI(LEFT, 0); mark(endLabel);
        } else {
            emitMovI(LEFT, 0); emitJump(Opcode.JMP, endLabel);
            mark(trueLabel); emitMovI(LEFT, 1); mark(endLabel);
        }
    }

    private void load(Xir.Value value, int targetRegister, Map<Xir.Value, Integer> slots) {
        if (value.name().startsWith("@")) {
            emitAddress(ADDRESS, value.name().substring(1)); emitMemory(Opcode.LOAD64, targetRegister, ADDRESS);
        } else {
            addressOfSlot(slots.get(value)); emitMemory(Opcode.LOAD64, targetRegister, ADDRESS);
        }
    }

    private void store(Xir.Value value, int sourceRegister, Map<Xir.Value, Integer> slots) {
        if (value.name().startsWith("@")) {
            emitAddress(ADDRESS, value.name().substring(1)); emitMemory(Opcode.STORE64, sourceRegister, ADDRESS);
        } else {
            addressOfSlot(slots.get(value)); emitMemory(Opcode.STORE64, sourceRegister, ADDRESS);
        }
    }

    private void addressOfSlot(Integer slot) {
        if (slot == null) throw new IllegalStateException("missing stack slot");
        emitMovI(ADDRESS, -8L * (slot + 1)); emitTri(Opcode.ADD, ADDRESS, FRAME_POINTER, ADDRESS);
    }

    private Map<Xir.Value, Integer> allocateSlots(Xir.Function function) {
        Map<Xir.Value, Integer> slots = new LinkedHashMap<>();
        for (Xir.Value parameter : function.parameters()) addSlot(slots, parameter);
        for (Xir.BasicBlock block : function.blocks()) {
            for (Xir.Instruction instruction : block.instructions()) {
                if (instruction instanceof Xir.Const x) addSlot(slots, x.result());
                else if (instruction instanceof Xir.Copy x) { addSlot(slots, x.target()); addSlot(slots, x.source()); }
                else if (instruction instanceof Xir.Unary x) { addSlot(slots, x.result()); addSlot(slots, x.operand()); }
                else if (instruction instanceof Xir.Binary x) { addSlot(slots, x.result()); addSlot(slots, x.left()); addSlot(slots, x.right()); }
                else if (instruction instanceof Xir.Call x) { if (x.result() != null) addSlot(slots, x.result()); x.arguments().forEach(v -> addSlot(slots, v)); }
            }
            if (block.terminator() instanceof Xir.Branch x) addSlot(slots, x.condition());
            else if (block.terminator() instanceof Xir.Return x && x.value() != null) addSlot(slots, x.value());
        }
        return slots;
    }

    private static void addSlot(Map<Xir.Value, Integer> slots, Xir.Value value) {
        if (!value.name().startsWith("@")) slots.computeIfAbsent(value, ignored -> slots.size());
    }

    private Opcode arithmetic(TokenType operator) {
        return switch (operator) {
            case PLUS -> Opcode.ADD; case MINUS -> Opcode.SUB; case STAR -> Opcode.MUL;
            case SLASH -> Opcode.DIV; case PERCENT -> Opcode.MOD; default -> null;
        };
    }

    private String stringSymbol(String value) {
        return strings.computeIfAbsent(value, ignored -> {
            String name = "$str." + strings.size();
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            symbols.add(new XObject.Symbol(name, XObject.Section.DATA, data.size(), bytes.length + 1, false));
            data.writeBytes(bytes); data.write(0); return name;
        });
    }

    private void emitAddress(int register, String symbol) {
        emitOpcode(Opcode.MOVI); emitByte(register); int offset = text.size(); emitI64(0);
        relocations.add(new XObject.Relocation(XObject.Section.TEXT, offset,
            XObject.RelocationType.ABS64, symbol, 0));
    }
    private void emitCall(String symbol) {
        emitOpcode(Opcode.CALL); int offset = text.size(); emitI32(0);
        relocations.add(new XObject.Relocation(XObject.Section.TEXT, offset,
            XObject.RelocationType.ABS32, symbol, 0));
    }
    private void emitJump(Opcode opcode, String symbol) {
        emitOpcode(opcode); int offset = text.size(); emitI32(0);
        relocations.add(new XObject.Relocation(XObject.Section.TEXT, offset,
            XObject.RelocationType.ABS32, symbol, 0));
    }
    private void mark(String name) {
        symbols.add(new XObject.Symbol(name, XObject.Section.TEXT, text.size(), 0, false));
    }
    private String blockLabel(Xir.Function function, String block) { return "$L." + function.name() + "." + block; }
    private String localLabel(String purpose) { return "$L.backend." + purpose + "." + internalLabel++; }

    private void emitOpcode(Opcode opcode) { emitByte(opcode.code()); }
    private void emitMovI(int register, long value) { emitOpcode(Opcode.MOVI); emitByte(register); emitI64(value); }
    private void emitMov(int dst, int src) { emitOpcode(Opcode.MOV); emitByte(dst); emitByte(src); }
    private void emitTri(Opcode opcode, int dst, int left, int right) {
        emitOpcode(opcode); emitByte(dst); emitByte(left); emitByte(right);
    }
    private void emitCmp(int left, int right) { emitOpcode(Opcode.CMP); emitByte(left); emitByte(right); }
    private void emitMemory(Opcode opcode, int value, int address) { emitOpcode(opcode); emitByte(value); emitByte(address); }
    private void emitPush(int register) { emitOpcode(Opcode.PUSH); emitByte(register); }
    private void emitPop(int register) { emitOpcode(Opcode.POP); emitByte(register); }
    private void emitByte(int value) { text.write(value); }
    private void emitI32(int value) { for (int shift = 0; shift < 32; shift += 8) text.write(value >>> shift); }
    private void emitI64(long value) { for (int shift = 0; shift < 64; shift += 8) text.write((int) (value >>> shift)); }
    private void alignData(int alignment) { while (data.size() % alignment != 0) data.write(0); }
    private void writeDataLong(long value) { for (int shift = 0; shift < 64; shift += 8) data.write((int) (value >>> shift)); }
}
