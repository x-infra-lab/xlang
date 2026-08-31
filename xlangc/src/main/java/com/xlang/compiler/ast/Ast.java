package com.xlang.compiler.ast;

import com.xlang.compiler.source.SourceSpan;
import com.xlang.compiler.token.TokenType;
import java.util.List;

/** Sealed syntax tree for the v0.2 language, including P9 aggregates. */
public final class Ast {
    private Ast() {}
    public interface Node { SourceSpan span(); }
    public sealed interface Item extends Node permits FnDecl, LetDecl, AggregateDecl {}
    public sealed interface Stmt extends Node permits LetDecl, ReturnStmt, IfStmt, WhileStmt, BreakStmt, ContinueStmt, Block, ExprStmt {}
    public sealed interface Expr extends Node permits LiteralExpr, NameExpr, UnaryExpr, BinaryExpr,
        AssignExpr, CallExpr, IndexExpr, MemberExpr, GroupExpr, SizeofExpr, CastExpr,
        ArrayLiteralExpr, AggregateLiteralExpr {}
    public record Program(List<Item> items, SourceSpan span) implements Node { public Program { items = List.copyOf(items); } }
    public record FnDecl(String name, List<Param> params, TypeRef returnType, Block body, SourceSpan span) implements Item { public FnDecl { params = List.copyOf(params); } }
    public record Param(String name, TypeRef type, SourceSpan span) implements Node {}
    public enum AggregateKind { STRUCT, UNION }
    public record AggregateDecl(AggregateKind kind, String name, List<FieldDecl> fields,
                                SourceSpan span) implements Item {
        public AggregateDecl { fields = List.copyOf(fields); }
    }
    public record FieldDecl(String name, TypeRef type, SourceSpan span) implements Node {}
    public sealed interface TypeRef extends Node permits NamedType, PointerType, ArrayType {}
    public record NamedType(String name, SourceSpan span) implements TypeRef {}
    public record PointerType(TypeRef target, SourceSpan span) implements TypeRef {}
    public record ArrayType(long length, TypeRef element, SourceSpan span) implements TypeRef {}
    public record LetDecl(String name, TypeRef type, Expr initializer, SourceSpan span) implements Item, Stmt {}
    public record Block(List<Stmt> statements, SourceSpan span) implements Stmt { public Block { statements = List.copyOf(statements); } }
    public record ReturnStmt(Expr value, SourceSpan span) implements Stmt {}
    public record IfStmt(Expr condition, Block thenBranch, Stmt elseBranch, SourceSpan span) implements Stmt {}
    public record WhileStmt(Expr condition, Block body, SourceSpan span) implements Stmt {}
    public record BreakStmt(SourceSpan span) implements Stmt {}
    public record ContinueStmt(SourceSpan span) implements Stmt {}
    public record ExprStmt(Expr expression, SourceSpan span) implements Stmt {}
    public record LiteralExpr(Object value, SourceSpan span) implements Expr {}
    public record NameExpr(String name, SourceSpan span) implements Expr {}
    public record UnaryExpr(TokenType operator, Expr operand, SourceSpan span) implements Expr {}
    public record BinaryExpr(Expr left, TokenType operator, Expr right, SourceSpan span) implements Expr {}
    public record AssignExpr(Expr target, TokenType operator, Expr value, SourceSpan span) implements Expr {}
    public record CallExpr(Expr callee, List<Expr> arguments, SourceSpan span) implements Expr { public CallExpr { arguments = List.copyOf(arguments); } }
    public record IndexExpr(Expr target, Expr index, SourceSpan span) implements Expr {}
    public record MemberExpr(Expr target, String member, SourceSpan span) implements Expr {}
    public record GroupExpr(Expr expression, SourceSpan span) implements Expr {}
    public record SizeofExpr(TypeRef type, SourceSpan span) implements Expr {}
    public record CastExpr(Expr expression, TypeRef target, SourceSpan span) implements Expr {}
    public record ArrayLiteralExpr(List<Expr> elements, SourceSpan span) implements Expr {
        public ArrayLiteralExpr { elements = List.copyOf(elements); }
    }
    public record AggregateLiteralExpr(String typeName, List<FieldInit> fields,
                                       SourceSpan span) implements Expr {
        public AggregateLiteralExpr { fields = List.copyOf(fields); }
    }
    public record FieldInit(String name, Expr value, SourceSpan span) implements Node {}
}
