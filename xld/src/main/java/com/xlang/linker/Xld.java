package com.xlang.linker;

/**
 * xld: the xlang linker (P7).
 *
 * <p>Consumes one or more {@code .xo} objects and produces a single {@code .xex}
 * executable image. Along the way it demonstrates:
 * <ul>
 *   <li>section merging ({@code .text}, {@code .data}, {@code .bss}),</li>
 *   <li>global symbol resolution across translation units,</li>
 *   <li>relocation application (patching addresses in {@code .text}),</li>
 *   <li>final layout with load addresses and permissions.</li>
 * </ul>
 *
 * <p>The whole point is that the linker is not magic. Every step is a plain
 * Java loop over a few arrays, and we log each patched byte so you can watch
 * relocations happen.
 */
public final class Xld {
    private Xld() {}

    public static String greeting() {
        return "xld P0 scaffold ready";
    }
}
