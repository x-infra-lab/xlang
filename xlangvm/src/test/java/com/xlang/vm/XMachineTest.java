package com.xlang.vm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class XMachineTest {
    @Test void runsArithmeticAndConditionalLoop() {
        byte[] program = new Assembler()
            .movi(0, 0).movi(1, 1).movi(2, 5)
            .tri(Opcode.ADD, 0, 0, 1).cmp(0, 2).jump(Opcode.JNZ, 30).halt().bytes();
        XMachine machine = machine(program);
        var result = machine.run();
        assertEquals(5, result.cpu().register(0));
        assertTrue(result.cpu().zero());
        assertTrue(result.cpu().halted());
        assertEquals(19, result.cpu().steps());
    }

    @Test void movesValuesThroughMemoryAndStack() {
        byte[] program = new Assembler()
            .movi(0, 123456789).movi(1, 128)
            .memory(Opcode.STORE64, 0, 1).memory(Opcode.LOAD64, 2, 1)
            .push(2).pop(3).halt().bytes();
        XMachine machine = machine(program);
        machine.run();
        assertEquals(123456789, machine.cpu().register(2));
        assertEquals(123456789, machine.cpu().register(3));
        assertEquals(123456789, machine.readLong(128));
        assertEquals(machine.ramSize(), machine.cpu().register(XCpu.STACK_POINTER));
    }

    @Test void callsAndReturnsUsingTheMachineStack() {
        byte[] program = new Assembler().jump(Opcode.CALL, 6).halt().movi(0, 42).ret().bytes();
        XMachine machine = machine(program);
        machine.run();
        assertEquals(42, machine.cpu().register(0));
        assertEquals(machine.ramSize(), machine.cpu().register(XCpu.STACK_POINTER));
    }

    @Test void tracesRawBytesDisassemblyAndPostState() {
        byte[] program = new Assembler().movi(0, 7).movi(1, 6).tri(Opcode.MUL, 0, 0, 1).halt().bytes();
        var result = machine(program).run(100, true);
        assertEquals(4, result.trace().size());
        assertTrue(result.trace().get(0).format().contains("movi r0, 7"));
        assertTrue(result.trace().get(2).format().contains("mul r0, r0, r1"));
        assertEquals(42, result.trace().get(2).after().register(0));
    }

    @Test void reportsDeterministicMachineFaults() {
        assertTrue(fault(new byte[] {(byte) 0xff}).getMessage().contains("unknown opcode"));
        assertTrue(fault(new byte[] {(byte) Opcode.MOVI.code(), 0}).getMessage().contains("truncated instruction"));
        assertTrue(fault(new Assembler().movi(0, 1).movi(1, 0).tri(Opcode.DIV, 2, 0, 1).halt().bytes())
            .getMessage().contains("division by zero"));
        assertTrue(fault(new Assembler().jump(Opcode.JMP, 999).bytes()).getMessage().contains("jump target"));
        assertTrue(fault(new Assembler().pop(0).halt().bytes()).getMessage().contains("stack underflow"));
    }

    @Test void enforcesInstructionLimitAndCanStepManually() {
        XMachine loop = machine(new Assembler().jump(Opcode.JMP, 0).bytes());
        MachineFault limit = assertThrows(MachineFault.class, () -> loop.run(3, false));
        assertTrue(limit.getMessage().contains("step limit"));

        XMachine stepped = machine(new Assembler().nop().halt().bytes());
        assertFalse(stepped.step().after().halted());
        assertTrue(stepped.step().after().halted());
        assertThrows(IllegalStateException.class, stepped::step);
    }

    @Test void parsesAndFormatsHexPrograms() {
        byte[] bytes = HexProgram.parse("0x10 00_ff");
        assertEquals("10 00 ff", HexProgram.format(bytes));
        assertThrows(IllegalArgumentException.class, () -> HexProgram.parse("123"));
        assertThrows(IllegalArgumentException.class, () -> HexProgram.parse("zz"));
    }

    private static XMachine machine(byte[] program) {
        XMachine machine = new XMachine(256);
        machine.load(program);
        return machine;
    }

    private static MachineFault fault(byte[] program) {
        return assertThrows(MachineFault.class, () -> machine(program).run());
    }
}
