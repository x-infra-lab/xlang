package com.xlang.runtime;

/**
 * xrt: the mini runtime / libc, later written in xlang itself (P8).
 *
 * <p>Java code here only exists to bootstrap the runtime story: it declares
 * which C-style symbols will need to appear (start, write, exit, malloc,
 * free, printf) and where they will live in {@code .xo} objects. Once we
 * can compile xlang source, the real bodies move into
 * {@code xrt/src/main/xlang/*.xl}.
 */
public final class Xrt {
    private Xrt() {}

    public static String greeting() {
        return "xrt P0 scaffold ready";
    }
}
