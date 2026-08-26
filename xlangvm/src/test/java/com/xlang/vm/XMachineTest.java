package com.xlang.vm;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class XMachineTest {
    @Test
    void scaffoldLoads() {
        assertTrue(XMachine.greeting().contains("P0"));
    }
}
