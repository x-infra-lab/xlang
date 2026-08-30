package com.xlang.compiler.token;

/**
 * Token categories used by both the lexer and the parser.
 *
 * <p>Keywords are separate types (not one generic {@code KEYWORD}) so the
 * parser can pattern-match on {@code FN}, {@code LET}, etc. directly.
 * The spec-reserved-but-P1-unused keywords ({@code STRUCT}, {@code SIZEOF},
 * {@code AS}) still get their own tokens so that later phases don't have
 * to touch the lexer.
 */
public enum TokenType {
    // literals
    INT_LIT, STRING_LIT, TRUE, FALSE, NULL,

    // identifiers
    IDENT,

    // keywords
    FN, LET, RETURN, IF, ELSE, WHILE, FOR, BREAK, CONTINUE,
    INT_TY, BOOL_TY, VOID_TY, STRING_TY,
    STRUCT, SIZEOF, AS,

    // punctuation
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, SEMICOLON, COLON, ARROW, DOT,

    // operators
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EQ_EQ, BANG_EQ, LT, LT_EQ, GT, GT_EQ,
    AMP_AMP, PIPE_PIPE, BANG,
    AMP, PIPE,
    ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN,

    // synthetic
    EOF;

    /** Whether this token type is a keyword that {@link Lexer} recognises. */
    public boolean isKeyword() {
        return switch (this) {
            case FN, LET, RETURN, IF, ELSE, WHILE, FOR, BREAK, CONTINUE,
                 TRUE, FALSE, NULL,
                 INT_TY, BOOL_TY, VOID_TY, STRING_TY,
                 STRUCT, SIZEOF, AS -> true;
            default -> false;
        };
    }
}
