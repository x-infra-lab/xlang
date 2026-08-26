package com.xlang.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class XrtTest {
    @Test
    void scaffoldLoads() {
        assertTrue(Xrt.greeting().contains("P0"));
    }
}
