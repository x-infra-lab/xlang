package com.xlang.compiler.sema;

import com.xlang.compiler.source.SourceSpan;
import java.util.List;

/** A declared name and its semantic type. */
public sealed interface Symbol permits Symbol.Variable, Symbol.Function {
    String name();
    SourceSpan span();

    record Variable(String name, Type type, SourceSpan span) implements Symbol {}

    record Function(String name, List<Type> parameterTypes, Type returnType,
                    SourceSpan span) implements Symbol {
        public Function {
            parameterTypes = List.copyOf(parameterTypes);
        }
    }
}
