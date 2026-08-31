package com.xlang.compiler.sema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** P8 compiler intrinsics and the source-level xrt ABI visible to applications. */
public final class BuiltinFunctions {
    public static final String SYSCALL = "__syscall";
    public static final String ADDRESS = "__address";
    public static final String LOAD64 = "__load64";
    public static final String STORE64 = "__store64";
    private static final Type VOID_POINTER = Type.pointer(Type.VOID);

    private static final Map<String, Signature> INTRINSICS = signatures(
        entry(SYSCALL, List.of(Type.INT, Type.INT, Type.INT, Type.INT), Type.INT),
        entry(ADDRESS, List.of(Type.STRING), VOID_POINTER),
        entry(LOAD64, List.of(VOID_POINTER), Type.INT),
        entry(STORE64, List.of(VOID_POINTER, Type.INT), Type.VOID)
    );
    private static final Map<String, Signature> RUNTIME = signatures(
        entry("start", List.of(), Type.INT),
        entry("write", List.of(Type.INT, VOID_POINTER, Type.INT), Type.INT),
        entry("exit", List.of(Type.INT), Type.VOID),
        entry("malloc", List.of(Type.INT), VOID_POINTER),
        entry("free", List.of(VOID_POINTER), Type.VOID),
        entry("printf", List.of(Type.STRING, Type.INT), Type.INT)
    );

    private BuiltinFunctions() {}

    public static Map<String, Signature> intrinsics() { return INTRINSICS; }
    public static Map<String, Signature> runtime() { return RUNTIME; }
    public static boolean isIntrinsic(String name) { return INTRINSICS.containsKey(name); }

    @SafeVarargs
    private static Map<String, Signature> signatures(Map.Entry<String, Signature>... entries) {
        Map<String, Signature> result = new LinkedHashMap<>();
        for (Map.Entry<String, Signature> entry : entries) result.put(entry.getKey(), entry.getValue());
        return Map.copyOf(result);
    }

    private static Map.Entry<String, Signature> entry(String name, List<Type> parameters, Type result) {
        return Map.entry(name, new Signature(parameters, result));
    }

    public record Signature(List<Type> parameters, Type result) {
        public Signature { parameters = List.copyOf(parameters); }
    }
}
