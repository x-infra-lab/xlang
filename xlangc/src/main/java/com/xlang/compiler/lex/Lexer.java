package com.xlang.compiler.lex;

import com.xlang.compiler.diag.Diagnostic;
import com.xlang.compiler.source.SourceSpan;
import com.xlang.compiler.token.Token;
import com.xlang.compiler.token.TokenType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A hand-written, single-pass lexer for xlang. */
public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
        Map.entry("fn", TokenType.FN), Map.entry("let", TokenType.LET),
        Map.entry("return", TokenType.RETURN), Map.entry("if", TokenType.IF),
        Map.entry("else", TokenType.ELSE), Map.entry("while", TokenType.WHILE),
        Map.entry("for", TokenType.FOR), Map.entry("break", TokenType.BREAK),
        Map.entry("continue", TokenType.CONTINUE), Map.entry("true", TokenType.TRUE),
        Map.entry("false", TokenType.FALSE), Map.entry("null", TokenType.NULL),
        Map.entry("int", TokenType.INT_TY), Map.entry("bool", TokenType.BOOL_TY),
        Map.entry("void", TokenType.VOID_TY), Map.entry("string", TokenType.STRING_TY),
        Map.entry("struct", TokenType.STRUCT), Map.entry("union", TokenType.UNION),
        Map.entry("sizeof", TokenType.SIZEOF), Map.entry("as", TokenType.AS));

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int current;
    private int line = 1;
    private int column = 1;

    public Lexer(String source) { this.source = source; }

    public LexResult lex() {
        while (!atEnd()) {
            int start = current, startLine = line, startColumn = column;
            char c = advance();
            switch (c) {
                case ' ', '\t', '\f' -> { }
                case '\r' -> { if (peek() == '\n') advance(); line++; column = 1; }
                case '\n' -> { line++; column = 1; }
                case '(' -> add(TokenType.LPAREN, start, startLine, startColumn);
                case ')' -> add(TokenType.RPAREN, start, startLine, startColumn);
                case '{' -> add(TokenType.LBRACE, start, startLine, startColumn);
                case '}' -> add(TokenType.RBRACE, start, startLine, startColumn);
                case '[' -> add(TokenType.LBRACKET, start, startLine, startColumn);
                case ']' -> add(TokenType.RBRACKET, start, startLine, startColumn);
                case ',' -> add(TokenType.COMMA, start, startLine, startColumn);
                case ';' -> add(TokenType.SEMICOLON, start, startLine, startColumn);
                case ':' -> add(TokenType.COLON, start, startLine, startColumn);
                case '.' -> add(TokenType.DOT, start, startLine, startColumn);
                case '+' -> add(match('=') ? TokenType.PLUS_ASSIGN : TokenType.PLUS, start, startLine, startColumn);
                case '-' -> add(match('>') ? TokenType.ARROW : match('=') ? TokenType.MINUS_ASSIGN : TokenType.MINUS, start, startLine, startColumn);
                case '*' -> add(match('=') ? TokenType.STAR_ASSIGN : TokenType.STAR, start, startLine, startColumn);
                case '%' -> add(match('=') ? TokenType.PERCENT_ASSIGN : TokenType.PERCENT, start, startLine, startColumn);
                case '=' -> add(match('=') ? TokenType.EQ_EQ : TokenType.ASSIGN, start, startLine, startColumn);
                case '!' -> add(match('=') ? TokenType.BANG_EQ : TokenType.BANG, start, startLine, startColumn);
                case '<' -> add(match('=') ? TokenType.LT_EQ : TokenType.LT, start, startLine, startColumn);
                case '>' -> add(match('=') ? TokenType.GT_EQ : TokenType.GT, start, startLine, startColumn);
                case '&' -> add(match('&') ? TokenType.AMP_AMP : TokenType.AMP, start, startLine, startColumn);
                case '|' -> add(match('|') ? TokenType.PIPE_PIPE : TokenType.PIPE, start, startLine, startColumn);
                case '/' -> slash(start, startLine, startColumn);
                case '"' -> string(start, startLine, startColumn);
                default -> {
                    if (isDigit(c)) number(start, startLine, startColumn);
                    else if (isIdentStart(c)) identifier(start, startLine, startColumn);
                    else error(start, startLine, startColumn, "unexpected character '" + c + "'");
                }
            }
        }
        SourceSpan eof = new SourceSpan(current, current, line, column);
        tokens.add(Token.of(TokenType.EOF, "", eof));
        return new LexResult(tokens, diagnostics);
    }

    private void slash(int start, int sl, int sc) {
        if (match('/')) { while (!atEnd() && peek() != '\n' && peek() != '\r') advance(); return; }
        if (match('*')) {
            while (!atEnd() && !(peek() == '*' && peekNext() == '/')) {
                if (peek() == '\n') { advance(); line++; column = 1; }
                else if (peek() == '\r') { advance(); if (peek() == '\n') advance(); line++; column = 1; }
                else advance();
            }
            if (atEnd()) error(start, sl, sc, "unterminated block comment");
            else { advance(); advance(); }
            return;
        }
        add(match('=') ? TokenType.SLASH_ASSIGN : TokenType.SLASH, start, sl, sc);
    }

    private void identifier(int start, int sl, int sc) {
        while (isIdentContinue(peek())) advance();
        String text = source.substring(start, current);
        add(KEYWORDS.getOrDefault(text, TokenType.IDENT), start, sl, sc);
    }

    private void number(int start, int sl, int sc) {
        int radix = 10;
        if (source.charAt(start) == '0' && (peek() == 'x' || peek() == 'X')) {
            advance(); radix = 16;
            int digits = current;
            while (isHex(peek())) advance();
            if (current == digits) { error(start, sl, sc, "hex literal requires at least one digit"); return; }
        } else while (isDigit(peek())) advance();
        String text = source.substring(start, current);
        try {
            String digits = radix == 16 ? text.substring(2) : text;
            tokens.add(new Token(TokenType.INT_LIT, text, Long.parseLong(digits, radix), span(start, sl, sc)));
        } catch (NumberFormatException ex) { error(start, sl, sc, "integer literal overflows i64"); }
    }

    private void string(int start, int sl, int sc) {
        StringBuilder value = new StringBuilder();
        boolean valid = true;
        while (!atEnd() && peek() != '"') {
            char c = advance();
            if (c == '\n' || c == '\r') { error(start, sl, sc, "newline in string literal"); valid = false; break; }
            if (c != '\\') { value.append(c); continue; }
            if (atEnd()) break;
            char escaped = advance();
            switch (escaped) {
                case '"' -> value.append('"'); case '\\' -> value.append('\\');
                case 'n' -> value.append('\n'); case 'r' -> value.append('\r');
                case 't' -> value.append('\t'); case '0' -> value.append('\0');
                default -> { error(current - 2, line, Math.max(1, column - 2), "unknown escape sequence \\" + escaped + "'"); valid = false; }
            }
        }
        if (peek() == '"') advance(); else if (valid) error(start, sl, sc, "unterminated string literal");
        if (valid) tokens.add(new Token(TokenType.STRING_LIT, source.substring(start, current), value.toString(), span(start, sl, sc)));
    }

    private void add(TokenType type, int start, int sl, int sc) {
        tokens.add(Token.of(type, source.substring(start, current), span(start, sl, sc)));
    }
    private void error(int start, int sl, int sc, String message) { diagnostics.add(Diagnostic.error(span(start, sl, sc), message)); }
    private SourceSpan span(int start, int sl, int sc) { return new SourceSpan(start, current, sl, sc); }
    private boolean atEnd() { return current >= source.length(); }
    private char peek() { return atEnd() ? '\0' : source.charAt(current); }
    private char peekNext() { return current + 1 >= source.length() ? '\0' : source.charAt(current + 1); }
    private char advance() { char c = source.charAt(current++); column++; return c; }
    private boolean match(char expected) { if (peek() != expected) return false; advance(); return true; }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isHex(char c) { return isDigit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'; }
    private static boolean isIdentStart(char c) { return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static boolean isIdentContinue(char c) { return isIdentStart(c) || isDigit(c); }
}
