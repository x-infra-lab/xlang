package com.xlang.compiler.parse;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
import com.xlang.compiler.source.SourceSpan;
import com.xlang.compiler.token.Token;
import com.xlang.compiler.token.TokenType;
import java.util.ArrayList;
import java.util.List;

/** Recursive-descent parser with statement-boundary error recovery. */
public final class Parser {
    private final List<Token> tokens;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int current;
    public Parser(List<Token> tokens) { this.tokens = List.copyOf(tokens); }

    public ParseResult parse() {
        List<Ast.Item> items = new ArrayList<>();
        while (!check(TokenType.EOF)) {
            try { items.add(item()); }
            catch (ParseError ignored) { synchronize(true); }
        }
        SourceSpan span = items.isEmpty() ? peek().span() : SourceSpan.merge(items.get(0).span(), previousOrPeek().span());
        return new ParseResult(new Ast.Program(items, span), diagnostics);
    }

    private Ast.Item item() {
        if (match(TokenType.FN)) return function(previous());
        if (match(TokenType.STRUCT, TokenType.UNION)) return aggregate(previous());
        if (match(TokenType.LET)) { Ast.LetDecl d = letDecl(previous()); consume(TokenType.SEMICOLON, "expected ';' after declaration"); return d; }
        throw error(peek(), "expected top-level 'fn', 'let', 'struct', or 'union'");
    }
    private Ast.AggregateDecl aggregate(Token start) {
        Token name = consume(TokenType.IDENT, "expected aggregate type name");
        consume(TokenType.LBRACE, "expected '{' after aggregate name");
        List<Ast.FieldDecl> fields = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            Token field = consume(TokenType.IDENT, "expected field name");
            consume(TokenType.COLON, "expected ':' after field name");
            Ast.TypeRef type = type();
            Token end = consume(TokenType.SEMICOLON, "expected ';' after field");
            fields.add(new Ast.FieldDecl(field.lexeme(), type, SourceSpan.merge(field.span(), end.span())));
        }
        Token end = consume(TokenType.RBRACE, "expected '}' after aggregate declaration");
        Ast.AggregateKind kind = start.type() == TokenType.STRUCT
            ? Ast.AggregateKind.STRUCT : Ast.AggregateKind.UNION;
        return new Ast.AggregateDecl(kind, name.lexeme(), fields,
            SourceSpan.merge(start.span(), end.span()));
    }
    private Ast.FnDecl function(Token start) {
        Token name = consume(TokenType.IDENT, "expected function name");
        consume(TokenType.LPAREN, "expected '(' after function name");
        List<Ast.Param> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) do {
            Token p = consume(TokenType.IDENT, "expected parameter name");
            consume(TokenType.COLON, "expected ':' after parameter name");
            Ast.TypeRef type = type(); params.add(new Ast.Param(p.lexeme(), type, SourceSpan.merge(p.span(), type.span())));
        } while (match(TokenType.COMMA));
        consume(TokenType.RPAREN, "expected ')' after parameters");
        Ast.TypeRef returnType = match(TokenType.ARROW) ? type() : null;
        Ast.Block body = block();
        return new Ast.FnDecl(name.lexeme(), params, returnType, body, SourceSpan.merge(start.span(), body.span()));
    }
    private Ast.TypeRef type() {
        if (match(TokenType.INT_TY, TokenType.BOOL_TY, TokenType.VOID_TY,
                  TokenType.STRING_TY, TokenType.IDENT)) {
            return new Ast.NamedType(previous().lexeme(), previous().span());
        }
        if (match(TokenType.STAR)) { Token start = previous(); Ast.TypeRef target = type(); return new Ast.PointerType(target, SourceSpan.merge(start.span(), target.span())); }
        if (match(TokenType.LBRACKET)) {
            Token start = previous(), length = consume(TokenType.INT_LIT, "expected array length");
            consume(TokenType.RBRACKET, "expected ']' after array length"); Ast.TypeRef element = type();
            return new Ast.ArrayType(length.asInt(), element, SourceSpan.merge(start.span(), element.span()));
        }
        throw error(peek(), "expected type");
    }
    private Ast.LetDecl letDecl(Token start) {
        Token name = consume(TokenType.IDENT, "expected variable name");
        Ast.TypeRef type = match(TokenType.COLON) ? type() : null;
        consume(TokenType.ASSIGN, "expected '=' in declaration"); Ast.Expr init = expression();
        return new Ast.LetDecl(name.lexeme(), type, init, SourceSpan.merge(start.span(), init.span()));
    }
    private Ast.Block block() {
        Token start = consume(TokenType.LBRACE, "expected '{'"); List<Ast.Stmt> statements = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            try { statements.add(statement()); } catch (ParseError ignored) { synchronize(false); }
        }
        Token end = consume(TokenType.RBRACE, "expected '}' after block");
        return new Ast.Block(statements, SourceSpan.merge(start.span(), end.span()));
    }
    private Ast.Stmt statement() {
        if (match(TokenType.LET)) { Ast.LetDecl d = letDecl(previous()); consume(TokenType.SEMICOLON, "expected ';' after declaration"); return d; }
        if (match(TokenType.RETURN)) {
            Token start = previous(); Ast.Expr value = check(TokenType.SEMICOLON) ? null : expression(); Token end = consume(TokenType.SEMICOLON, "expected ';' after return");
            return new Ast.ReturnStmt(value, SourceSpan.merge(start.span(), end.span()));
        }
        if (match(TokenType.IF)) return ifStmt(previous());
        if (match(TokenType.WHILE)) {
            Token start = previous(); consume(TokenType.LPAREN, "expected '(' after while"); Ast.Expr cond = expression(); consume(TokenType.RPAREN, "expected ')' after condition"); Ast.Block body = block();
            return new Ast.WhileStmt(cond, body, SourceSpan.merge(start.span(), body.span()));
        }
        if (match(TokenType.BREAK, TokenType.CONTINUE)) {
            Token keyword = previous(), end = consume(TokenType.SEMICOLON, "expected ';'"); SourceSpan span = SourceSpan.merge(keyword.span(), end.span());
            return keyword.type() == TokenType.BREAK ? new Ast.BreakStmt(span) : new Ast.ContinueStmt(span);
        }
        if (check(TokenType.LBRACE)) return block();
        Ast.Expr expr = expression(); Token end = consume(TokenType.SEMICOLON, "expected ';' after expression");
        return new Ast.ExprStmt(expr, SourceSpan.merge(expr.span(), end.span()));
    }
    private Ast.IfStmt ifStmt(Token start) {
        consume(TokenType.LPAREN, "expected '(' after if"); Ast.Expr cond = expression(); consume(TokenType.RPAREN, "expected ')' after condition"); Ast.Block then = block(); Ast.Stmt otherwise = null;
        if (match(TokenType.ELSE)) otherwise = match(TokenType.IF) ? ifStmt(previous()) : block();
        SourceSpan end = otherwise == null ? then.span() : otherwise.span();
        return new Ast.IfStmt(cond, then, otherwise, SourceSpan.merge(start.span(), end));
    }

    private Ast.Expr expression() { return assignment(); }
    private Ast.Expr assignment() {
        Ast.Expr left = logicOr();
        if (match(TokenType.ASSIGN, TokenType.PLUS_ASSIGN, TokenType.MINUS_ASSIGN, TokenType.STAR_ASSIGN, TokenType.SLASH_ASSIGN, TokenType.PERCENT_ASSIGN)) {
            Token op = previous(); Ast.Expr right = assignment(); return new Ast.AssignExpr(left, op.type(), right, SourceSpan.merge(left.span(), right.span()));
        } return left;
    }
    private Ast.Expr logicOr() { return binary(this::logicAnd, TokenType.PIPE_PIPE); }
    private Ast.Expr logicAnd() { return binary(this::equality, TokenType.AMP_AMP); }
    private Ast.Expr equality() { return binary(this::relational, TokenType.EQ_EQ, TokenType.BANG_EQ); }
    private Ast.Expr relational() { return binary(this::additive, TokenType.LT, TokenType.LT_EQ, TokenType.GT, TokenType.GT_EQ); }
    private Ast.Expr additive() { return binary(this::multiplicative, TokenType.PLUS, TokenType.MINUS); }
    private Ast.Expr multiplicative() { return binary(this::cast, TokenType.STAR, TokenType.SLASH, TokenType.PERCENT); }
    private Ast.Expr cast() {
        Ast.Expr expression = unary();
        while (match(TokenType.AS)) {
            Ast.TypeRef target = type();
            expression = new Ast.CastExpr(expression, target,
                SourceSpan.merge(expression.span(), target.span()));
        }
        return expression;
    }
    private Ast.Expr unary() {
        if (match(TokenType.SIZEOF)) {
            Token start = previous(); consume(TokenType.LPAREN, "expected '(' after sizeof");
            Ast.TypeRef measured = type(); Token end = consume(TokenType.RPAREN, "expected ')' after sizeof type");
            return new Ast.SizeofExpr(measured, SourceSpan.merge(start.span(), end.span()));
        }
        if (match(TokenType.MINUS, TokenType.BANG, TokenType.STAR, TokenType.AMP)) { Token op = previous(); Ast.Expr operand = unary(); return new Ast.UnaryExpr(op.type(), operand, SourceSpan.merge(op.span(), operand.span())); }
        return postfix();
    }
    private Ast.Expr postfix() {
        Ast.Expr expr = primary();
        while (true) {
            if (match(TokenType.LPAREN)) {
                List<Ast.Expr> args = new ArrayList<>(); if (!check(TokenType.RPAREN)) do { args.add(expression()); } while (match(TokenType.COMMA));
                Token end = consume(TokenType.RPAREN, "expected ')' after arguments"); expr = new Ast.CallExpr(expr, args, SourceSpan.merge(expr.span(), end.span()));
            } else if (match(TokenType.LBRACKET)) { Ast.Expr index = expression(); Token end = consume(TokenType.RBRACKET, "expected ']' after index"); expr = new Ast.IndexExpr(expr, index, SourceSpan.merge(expr.span(), end.span())); }
            else if (match(TokenType.DOT)) { Token member = consume(TokenType.IDENT, "expected member name after '.'"); expr = new Ast.MemberExpr(expr, member.lexeme(), SourceSpan.merge(expr.span(), member.span())); }
            else break;
        } return expr;
    }
    private Ast.Expr primary() {
        if (match(TokenType.INT_LIT, TokenType.STRING_LIT)) return new Ast.LiteralExpr(previous().value(), previous().span());
        if (match(TokenType.TRUE)) return new Ast.LiteralExpr(true, previous().span());
        if (match(TokenType.FALSE)) return new Ast.LiteralExpr(false, previous().span());
        if (match(TokenType.NULL)) return new Ast.LiteralExpr(null, previous().span());
        if (match(TokenType.IDENT)) {
            Token name = previous();
            if (!match(TokenType.LBRACE)) return new Ast.NameExpr(name.lexeme(), name.span());
            List<Ast.FieldInit> fields = new ArrayList<>();
            if (!check(TokenType.RBRACE)) do {
                Token field = consume(TokenType.IDENT, "expected aggregate field name");
                consume(TokenType.COLON, "expected ':' after aggregate field name");
                Ast.Expr value = expression();
                fields.add(new Ast.FieldInit(field.lexeme(), value,
                    SourceSpan.merge(field.span(), value.span())));
            } while (match(TokenType.COMMA) && !check(TokenType.RBRACE));
            Token end = consume(TokenType.RBRACE, "expected '}' after aggregate literal");
            return new Ast.AggregateLiteralExpr(name.lexeme(), fields,
                SourceSpan.merge(name.span(), end.span()));
        }
        if (match(TokenType.LBRACKET)) {
            Token start = previous(); List<Ast.Expr> elements = new ArrayList<>();
            if (!check(TokenType.RBRACKET)) do { elements.add(expression()); }
                while (match(TokenType.COMMA) && !check(TokenType.RBRACKET));
            Token end = consume(TokenType.RBRACKET, "expected ']' after array literal");
            return new Ast.ArrayLiteralExpr(elements, SourceSpan.merge(start.span(), end.span()));
        }
        if (match(TokenType.LPAREN)) { Token start = previous(); Ast.Expr inner = expression(); Token end = consume(TokenType.RPAREN, "expected ')' after expression"); return new Ast.GroupExpr(inner, SourceSpan.merge(start.span(), end.span())); }
        throw error(peek(), "expected expression");
    }
    private Ast.Expr binary(ExprParser operand, TokenType... operators) {
        Ast.Expr expr = operand.parse(); while (match(operators)) { Token op = previous(); Ast.Expr right = operand.parse(); expr = new Ast.BinaryExpr(expr, op.type(), right, SourceSpan.merge(expr.span(), right.span())); } return expr;
    }
    private void synchronize(boolean topLevel) {
        while (!check(TokenType.EOF)) {
            if (topLevel && (check(TokenType.FN) || check(TokenType.LET)
                    || check(TokenType.STRUCT) || check(TokenType.UNION))) return;
            if (!topLevel && (check(TokenType.LET) || check(TokenType.RETURN) || check(TokenType.IF) || check(TokenType.WHILE) || check(TokenType.BREAK) || check(TokenType.CONTINUE) || check(TokenType.RBRACE))) return;
            advance();
            if (previous().type() == TokenType.SEMICOLON) return;
        }
    }
    private ParseError error(Token token, String message) { diagnostics.add(Diagnostic.error(token.span(), message)); return new ParseError(); }
    private Token consume(TokenType type, String message) { if (check(type)) return advance(); throw error(peek(), message); }
    private boolean match(TokenType... types) { for (TokenType type : types) if (check(type)) { advance(); return true; } return false; }
    private boolean check(TokenType type) { return peek().type() == type; }
    private Token advance() { if (!check(TokenType.EOF)) current++; return previous(); }
    private Token peek() { return tokens.get(current); }
    private Token previous() { return tokens.get(current - 1); }
    private Token previousOrPeek() { return current == 0 ? peek() : previous(); }
    @FunctionalInterface private interface ExprParser { Ast.Expr parse(); }
    private static final class ParseError extends RuntimeException { private static final long serialVersionUID = 1L; }
}
