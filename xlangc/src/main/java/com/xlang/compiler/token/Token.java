package com.xlang.compiler.token;

import com.xlang.compiler.source.SourceSpan;

/**
 * A lexed token.
 *
 * <p>{@code lexeme} is the exact source text that produced the token,
 * preserved so error messages can quote it verbatim. {@code value} holds
 * a decoded payload for literals:
 * <ul>
 *   <li>{@link TokenType#INT_LIT}: {@link Long} value (i64).</li>
 *   <li>{@link TokenType#STRING_LIT}: the {@link String} with escapes
 *       processed.</li>
 *   <li>Every other token: {@code null}.</li>
 * </ul>
 */
public record Token(TokenType type, String lexeme, Object value, SourceSpan span) {

    /** Convenience constructor for tokens that carry no decoded payload. */
    public static Token of(TokenType type, String lexeme, SourceSpan span) {
        return new Token(type, lexeme, null, span);
    }

    public long asInt() {
        if (type != TokenType.INT_LIT) {
            throw new IllegalStateException("asInt on non-INT_LIT token: " + type);
        }
        return (Long) value;
    }

    public String asString() {
        if (type != TokenType.STRING_LIT) {
            throw new IllegalStateException("asString on non-STRING_LIT token: " + type);
        }
        return (String) value;
    }

    @Override
    public String toString() {
        String payload = value == null ? lexeme : String.valueOf(value);
        return type + "(" + payload + ")@" + span;
    }
}
