package com.xlang.compiler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class XlangcTest {
    @Test
    void scaffoldLoads() {
        assertTrue(Xlangc.greeting().contains("P0"));
    }
}
