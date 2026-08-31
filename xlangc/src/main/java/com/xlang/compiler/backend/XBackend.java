package com.xlang.compiler.backend;

import com.xlang.compiler.object.XObject;
import com.xlang.compiler.sema.BuiltinFunctions;
import com.xlang.compiler.sema.LayoutEngine;
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

/** Readable backend from typed XIR to relocatable XMachine code, extended for P9 memory values. */
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
    private final LayoutEngine layouts = new LayoutEngine();
    private int internalLabel;

    public XObject compile(Xir.Module module) {
        for (Xir.Global global : module.globals()) {
            int alignment = layouts.layout(global.type()).alignment();
            int size = checkedSize(layouts.layout(global.type()).size());
            alignData(alignment);
            symbols.add(new XObject.Symbol(global.name(), XObject.Section.DATA, data.size(), size, true));
            for (int index = 0; index < size; index++) data.write(0);
        }
        for (Xir.Function function : module.functions()) compileFunction(function);
        return new XObject(text.toByteArray(), data.toByteArray(), symbols, relocations);
    }

    private void compileFunction(Xir.Function function) {
        FrameLayout frame = allocateSlots(function);
        symbols.add(new XObject.Symbol(function.name(), XObject.Section.TEXT, text.size(), 0,
            !function.name().startsWith("$")));
        emitPush(FRAME_POINTER);
        emitMov(FRAME_POINTER, STACK_POINTER);
        int frameSize = frame.size();
        if (frameSize > 0) {
            emitMovI(ADDRESS, frameSize);
            emitTri(Opcode.SUB, STACK_POINTER, STACK_POINTER, ADDRESS);
        }
        for (int index = 0; index < function.parameters().size(); index++) {
            emitMovI(ADDRESS, 16L + index * 8L);
            emitTri(Opcode.ADD, ADDRESS, FRAME_POINTER, ADDRESS);
            emitMemory(Opcode.LOAD64, LEFT, ADDRESS);
            store(function.parameters().get(index), LEFT, frame);
        }
        for (Xir.BasicBlock block : function.blocks()) {
            mark(blockLabel(function, block.label()));
            for (Xir.Instruction instruction : block.instructions()) compileInstruction(instruction, frame);
            compileTerminator(function, block.terminator(), frame);
        }
    }

    private void compileInstruction(Xir.Instruction instruction, FrameLayout frame) {
        if (instruction instanceof Xir.Const constant) {
            if (constant.value() instanceof String string) emitAddress(LEFT, stringSymbol(string));
            else emitMovI(LEFT, constant.value() instanceof Boolean bool ? (bool ? 1 : 0) : (Long) constant.value());
            store(constant.result(), LEFT, frame);
        } else if (instruction instanceof Xir.Copy copy) {
            load(copy.source(), LEFT, frame); store(copy.target(), LEFT, frame);
        } else if (instruction instanceof Xir.Unary unary) {
            load(unary.operand(), LEFT, frame);
            if (unary.operator() == TokenType.MINUS) {
                emitMovI(RIGHT, 0); emitTri(Opcode.SUB, LEFT, RIGHT, LEFT);
            } else if (unary.operator() == TokenType.BANG) {
                emitMovI(RIGHT, 0); emitCmp(LEFT, RIGHT); emitBoolean(TokenType.EQ_EQ);
            } else throw new IllegalStateException("unsupported checked unary operator " + unary.operator());
            store(unary.result(), LEFT, frame);
        } else if (instruction instanceof Xir.Binary binary) {
            load(binary.left(), LEFT, frame); load(binary.right(), RIGHT, frame);
            Opcode arithmetic = arithmetic(binary.operator());
            if (arithmetic != null) emitTri(arithmetic, LEFT, LEFT, RIGHT);
            else { emitCmp(LEFT, RIGHT); emitBoolean(binary.operator()); }
            store(binary.result(), LEFT, frame);
        } else if (instruction instanceof Xir.Call call) {
            if (compileIntrinsic(call, frame)) return;
            for (int index = call.arguments().size() - 1; index >= 0; index--) {
                load(call.arguments().get(index), LEFT, frame); emitPush(LEFT);
            }
            emitCall(call.function());
            if (!call.arguments().isEmpty()) {
                emitMovI(ADDRESS, call.arguments().size() * 8L);
                emitTri(Opcode.ADD, STACK_POINTER, STACK_POINTER, ADDRESS);
            }
            if (call.result() != null) store(call.result(), RETURN, frame);
        } else if (instruction instanceof Xir.Allocate allocation) {
            Slot storage = frame.allocations().get(allocation.result());
            addressOfSlot(storage);
            emitMov(LEFT, ADDRESS);
            store(allocation.result(), LEFT, frame);
            zero(storage);
        } else if (instruction instanceof Xir.GlobalAddress address) {
            emitAddress(LEFT, address.symbol());
            store(address.result(), LEFT, frame);
        } else if (instruction instanceof Xir.AddressOf address) {
            storageAddress(address.target(), LEFT, frame);
            store(address.result(), LEFT, frame);
        } else if (instruction instanceof Xir.PointerOffset offset) {
            load(offset.base(), LEFT, frame); load(offset.byteOffset(), RIGHT, frame);
            emitTri(Opcode.ADD, LEFT, LEFT, RIGHT);
            store(offset.result(), LEFT, frame);
        } else if (instruction instanceof Xir.Load loaded) {
            load(loaded.address(), ADDRESS, frame);
            emitMemory(memoryLoad(loaded.result().type()), LEFT, ADDRESS);
            store(loaded.result(), LEFT, frame);
        } else if (instruction instanceof Xir.Store stored) {
            load(stored.source(), LEFT, frame); load(stored.address(), ADDRESS, frame);
            emitMemory(memoryStore(stored.source().type()), LEFT, ADDRESS);
        } else if (instruction instanceof Xir.MemCopy copy) {
            copyMemory(copy, frame);
        }
    }

    private boolean compileIntrinsic(Xir.Call call, FrameLayout frame) {
        if (!BuiltinFunctions.isIntrinsic(call.function())) return false;
        switch (call.function()) {
            case BuiltinFunctions.SYSCALL -> {
                for (int index = 0; index < call.arguments().size(); index++) {
                    load(call.arguments().get(index), index, frame);
                }
                emitOpcode(Opcode.SYSCALL);
                if (call.result() != null) store(call.result(), RETURN, frame);
            }
            case BuiltinFunctions.ADDRESS -> {
                load(call.arguments().get(0), LEFT, frame);
                store(call.result(), LEFT, frame);
            }
            case BuiltinFunctions.LOAD64 -> {
                load(call.arguments().get(0), ADDRESS, frame);
                emitMemory(Opcode.LOAD64, LEFT, ADDRESS);
                store(call.result(), LEFT, frame);
            }
            case BuiltinFunctions.STORE64 -> {
                load(call.arguments().get(1), LEFT, frame);
                load(call.arguments().get(0), ADDRESS, frame);
                emitMemory(Opcode.STORE64, LEFT, ADDRESS);
            }
            default -> throw new IllegalStateException("unknown intrinsic " + call.function());
        }
        return true;
    }

    private void compileTerminator(Xir.Function function, Xir.Terminator terminator,
                                   FrameLayout frame) {
        if (terminator instanceof Xir.Jump jump) {
            emitJump(Opcode.JMP, blockLabel(function, jump.target()));
        } else if (terminator instanceof Xir.Branch branch) {
            load(branch.condition(), LEFT, frame); emitMovI(RIGHT, 0); emitCmp(LEFT, RIGHT);
            emitJump(Opcode.JNZ, blockLabel(function, branch.whenTrue()));
            emitJump(Opcode.JMP, blockLabel(function, branch.whenFalse()));
        } else if (terminator instanceof Xir.Return returned) {
            if (returned.value() != null) load(returned.value(), RETURN, frame);
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

    private void load(Xir.Value value, int targetRegister, FrameLayout frame) {
        if (value.name().startsWith("@")) {
            emitAddress(ADDRESS, value.name().substring(1));
            emitMemory(memoryLoad(value.type()), targetRegister, ADDRESS);
        } else {
            addressOfSlot(frame.values().get(value));
            emitMemory(memoryLoad(value.type()), targetRegister, ADDRESS);
        }
    }

    private void store(Xir.Value value, int sourceRegister, FrameLayout frame) {
        if (value.name().startsWith("@")) {
            emitAddress(ADDRESS, value.name().substring(1));
            emitMemory(memoryStore(value.type()), sourceRegister, ADDRESS);
        } else {
            addressOfSlot(frame.values().get(value));
            emitMemory(memoryStore(value.type()), sourceRegister, ADDRESS);
        }
    }

    private void storageAddress(Xir.Value value, int targetRegister, FrameLayout frame) {
        if (value.name().startsWith("@")) emitAddress(targetRegister, value.name().substring(1));
        else {
            addressOfSlot(frame.values().get(value));
            if (targetRegister != ADDRESS) emitMov(targetRegister, ADDRESS);
        }
    }

    private void addressOfSlot(Slot slot) {
        if (slot == null) throw new IllegalStateException("missing stack slot");
        emitMovI(ADDRESS, -slot.endOffset());
        emitTri(Opcode.ADD, ADDRESS, FRAME_POINTER, ADDRESS);
    }

    private FrameLayout allocateSlots(Xir.Function function) {
        FrameBuilder builder = new FrameBuilder();
        for (Xir.Value parameter : function.parameters()) builder.addValue(parameter);
        for (Xir.BasicBlock block : function.blocks()) {
            for (Xir.Instruction instruction : block.instructions()) {
                addInstructionValues(builder, instruction);
            }
            if (block.terminator() instanceof Xir.Branch x) builder.addValue(x.condition());
            else if (block.terminator() instanceof Xir.Return x && x.value() != null) builder.addValue(x.value());
        }
        for (Xir.BasicBlock block : function.blocks()) {
            for (Xir.Instruction instruction : block.instructions()) {
                if (instruction instanceof Xir.Allocate allocation) {
                    builder.addAllocation(allocation.result(), allocation.allocatedType());
                }
            }
        }
        return builder.finish();
    }

    private void addInstructionValues(FrameBuilder builder, Xir.Instruction instruction) {
        if (instruction instanceof Xir.Const x) builder.addValue(x.result());
        else if (instruction instanceof Xir.Copy x) { builder.addValue(x.target()); builder.addValue(x.source()); }
        else if (instruction instanceof Xir.Unary x) { builder.addValue(x.result()); builder.addValue(x.operand()); }
        else if (instruction instanceof Xir.Binary x) { builder.addValue(x.result()); builder.addValue(x.left()); builder.addValue(x.right()); }
        else if (instruction instanceof Xir.Call x) { if (x.result() != null) builder.addValue(x.result()); x.arguments().forEach(builder::addValue); }
        else if (instruction instanceof Xir.Allocate x) builder.addValue(x.result());
        else if (instruction instanceof Xir.GlobalAddress x) builder.addValue(x.result());
        else if (instruction instanceof Xir.AddressOf x) { builder.addValue(x.result()); builder.addValue(x.target()); }
        else if (instruction instanceof Xir.PointerOffset x) { builder.addValue(x.result()); builder.addValue(x.base()); builder.addValue(x.byteOffset()); }
        else if (instruction instanceof Xir.Load x) { builder.addValue(x.result()); builder.addValue(x.address()); }
        else if (instruction instanceof Xir.Store x) { builder.addValue(x.address()); builder.addValue(x.source()); }
        else if (instruction instanceof Xir.MemCopy x) { builder.addValue(x.targetAddress()); builder.addValue(x.sourceAddress()); }
    }

    private void copyMemory(Xir.MemCopy copy, FrameLayout frame) {
        int size = checkedSize(copy.size());
        int offset = 0;
        while (offset + 8 <= size) {
            copyChunk(copy, frame, offset, Opcode.LOAD64, Opcode.STORE64);
            offset += 8;
        }
        while (offset < size) {
            copyChunk(copy, frame, offset, Opcode.LOAD8, Opcode.STORE8);
            offset++;
        }
    }

    private void zero(Slot slot) {
        emitMovI(LEFT, 0);
        int offset = 0;
        while (offset + 8 <= slot.size()) {
            addressOfSlot(slot);
            if (offset != 0) { emitMovI(RIGHT, offset); emitTri(Opcode.ADD, ADDRESS, ADDRESS, RIGHT); }
            emitMemory(Opcode.STORE64, LEFT, ADDRESS);
            offset += 8;
        }
        while (offset < slot.size()) {
            addressOfSlot(slot);
            if (offset != 0) { emitMovI(RIGHT, offset); emitTri(Opcode.ADD, ADDRESS, ADDRESS, RIGHT); }
            emitMemory(Opcode.STORE8, LEFT, ADDRESS);
            offset++;
        }
    }

    private void copyChunk(Xir.MemCopy copy, FrameLayout frame, int offset,
                           Opcode loadOpcode, Opcode storeOpcode) {
        load(copy.targetAddress(), RIGHT, frame);
        load(copy.sourceAddress(), LEFT, frame);
        if (offset != 0) {
            emitMovI(ADDRESS, offset);
            emitTri(Opcode.ADD, RIGHT, RIGHT, ADDRESS);
            emitTri(Opcode.ADD, LEFT, LEFT, ADDRESS);
        }
        emitMemory(loadOpcode, ADDRESS, LEFT);
        emitMemory(storeOpcode, ADDRESS, RIGHT);
    }

    private Opcode memoryLoad(Type type) {
        int size = checkedSize(layouts.layout(type).size());
        if (size == 1) return Opcode.LOAD8;
        if (size == 8) return Opcode.LOAD64;
        throw new IllegalStateException("cannot load aggregate value " + type.displayName());
    }

    private Opcode memoryStore(Type type) {
        return memoryLoad(type) == Opcode.LOAD8 ? Opcode.STORE8 : Opcode.STORE64;
    }

    private static int checkedSize(long size) {
        if (size < 0 || size > Integer.MAX_VALUE) throw new IllegalArgumentException("object is too large");
        return (int) size;
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

    private final class FrameBuilder {
        private final Map<Xir.Value, Slot> values = new LinkedHashMap<>();
        private final Map<Xir.Value, Slot> allocations = new LinkedHashMap<>();
        private int cursor;

        void addValue(Xir.Value value) {
            if (value == null || value.name().startsWith("@") || values.containsKey(value)) return;
            values.put(value, reserve(value.type()));
        }
        void addAllocation(Xir.Value value, Type type) {
            allocations.computeIfAbsent(value, ignored -> reserve(type));
        }
        private Slot reserve(Type type) {
            int alignment = layouts.layout(type).alignment();
            int size = checkedSize(layouts.layout(type).size());
            cursor = align(cursor, alignment);
            cursor = Math.addExact(cursor, size);
            return new Slot(cursor, size);
        }
        FrameLayout finish() { return new FrameLayout(values, allocations, align(cursor, 8)); }
    }

    private static int align(int value, int alignment) {
        return Math.multiplyExact(Math.floorDiv(Math.addExact(value, alignment - 1), alignment), alignment);
    }

    private record Slot(int endOffset, int size) {}
    private record FrameLayout(Map<Xir.Value, Slot> values,
                               Map<Xir.Value, Slot> allocations, int size) {
        private FrameLayout {
            values = Map.copyOf(values);
            allocations = Map.copyOf(allocations);
        }
    }
}
