package com.xlang.compiler.source;

/**
 * A half-open source span [start, end) into a single source file.
 *
 * <p>UTF-16 character offsets are the primary identity because they match
 * Java's {@link String} indexing and play nicely with substring slicing
 * during lexing; {@code line} and {@code column} are
 * cached so diagnostics can print human-readable positions without a
 * second pass over the source.
 *
 * <p>Lines are 1-based, columns are 1-based, columns count UTF-16 chars.
 * Good enough for a teaching toolchain; real compilers usually count
 * grapheme clusters.
 */
public record SourceSpan(int startOffset, int endOffset,
                         int startLine, int startColumn) {

    public SourceSpan {
        if (endOffset < startOffset) {
            throw new IllegalArgumentException(
                "endOffset (" + endOffset + ") < startOffset (" + startOffset + ")");
        }
        if (startLine < 1 || startColumn < 1) {
            throw new IllegalArgumentException(
                "line/column are 1-based, got line=" + startLine + " col=" + startColumn);
        }
    }

    /** Length of the span in UTF-16 code units. */
    public int length() {
        return endOffset - startOffset;
    }

    /** Merge two spans; the result covers from {@code a.start} to {@code b.end}. */
    public static SourceSpan merge(SourceSpan a, SourceSpan b) {
        return new SourceSpan(a.startOffset, b.endOffset, a.startLine, a.startColumn);
    }

    @Override
    public String toString() {
        return startLine + ":" + startColumn + "(" + startOffset + ".." + endOffset + ")";
    }
}
