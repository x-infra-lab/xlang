package com.xlang.runtime;

import com.xlang.compiler.Xlangc;
import com.xlang.compiler.object.XObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/** Loads and compiles the mini libc whose implementation lives in {@code xrt.xl}. */
public final class Xrt {
    public static final String ENTRY_SYMBOL = "start";
    private static final String SOURCE_RESOURCE = "/xrt.xl";

    private Xrt() {}

    public static String source() {
        try (InputStream input = Xrt.class.getResourceAsStream(SOURCE_RESOURCE)) {
            if (input == null) throw new IllegalStateException("missing bundled xrt source " + SOURCE_RESOURCE);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read bundled xrt source", exception);
        }
    }

    /** Returns the deterministically compiled runtime object. */
    public static XObject object() {
        return RuntimeObjectHolder.OBJECT;
    }

    private static XObject compileRuntime() {
        var result = Xlangc.compileLibrary(source());
        if (result.hasErrors()) {
            String messages = result.diagnostics().stream().map(diagnostic -> diagnostic.message())
                .collect(Collectors.joining("; "));
            throw new IllegalStateException("bundled xrt source does not compile: " + messages);
        }
        return result.object();
    }

    private static final class RuntimeObjectHolder {
        private static final XObject OBJECT = compileRuntime();
        private RuntimeObjectHolder() {}
    }
}
