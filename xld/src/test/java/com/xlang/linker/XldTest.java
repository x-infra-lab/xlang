package com.xlang.linker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class XldTest {
    @Test
    void scaffoldLoads() {
        assertTrue(Xld.greeting().contains("P0"));
    }
}
