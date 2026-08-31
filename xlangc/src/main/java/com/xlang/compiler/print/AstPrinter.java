package com.xlang.compiler.print;

import com.xlang.compiler.ast.Ast;
import java.util.List;

/** Stable, indentation-based AST rendering for teaching and tests. */
public final class AstPrinter {
    private final StringBuilder out = new StringBuilder();
    private AstPrinter() {}
    public static String print(Ast.Program program) { AstPrinter p = new AstPrinter(); p.node(program, 0); return p.out.toString(); }
    private void line(int depth, String text) { out.append("  ".repeat(depth)).append(text).append('\n'); }
    private void node(Ast.Node n, int d) {
        if (n instanceof Ast.Program x) { line(d, "Program"); each(x.items(), d + 1); }
        else if (n instanceof Ast.FnDecl x) { line(d, "FnDecl " + x.name()); for (Ast.Param p : x.params()) node(p, d + 1); if (x.returnType() != null) { line(d + 1, "ReturnType"); node(x.returnType(), d + 2); } node(x.body(), d + 1); }
        else if (n instanceof Ast.Param x) { line(d, "Param " + x.name()); node(x.type(), d + 1); }
        else if (n instanceof Ast.AggregateDecl x) { line(d, x.kind() + " " + x.name()); each(x.fields(), d + 1); }
        else if (n instanceof Ast.FieldDecl x) { line(d, "Field " + x.name()); node(x.type(), d + 1); }
        else if (n instanceof Ast.NamedType x) line(d, "Type " + x.name());
        else if (n instanceof Ast.PointerType x) { line(d, "PointerType"); node(x.target(), d + 1); }
        else if (n instanceof Ast.ArrayType x) { line(d, "ArrayType " + x.length()); node(x.element(), d + 1); }
        else if (n instanceof Ast.LetDecl x) { line(d, "LetDecl " + x.name()); if (x.type() != null) node(x.type(), d + 1); node(x.initializer(), d + 1); }
        else if (n instanceof Ast.Block x) { line(d, "Block"); each(x.statements(), d + 1); }
        else if (n instanceof Ast.ReturnStmt x) { line(d, "ReturnStmt"); if (x.value() != null) node(x.value(), d + 1); }
        else if (n instanceof Ast.IfStmt x) { line(d, "IfStmt"); node(x.condition(), d + 1); node(x.thenBranch(), d + 1); if (x.elseBranch() != null) node(x.elseBranch(), d + 1); }
        else if (n instanceof Ast.WhileStmt x) { line(d, "WhileStmt"); node(x.condition(), d + 1); node(x.body(), d + 1); }
        else if (n instanceof Ast.BreakStmt) line(d, "BreakStmt");
        else if (n instanceof Ast.ContinueStmt) line(d, "ContinueStmt");
        else if (n instanceof Ast.ExprStmt x) { line(d, "ExprStmt"); node(x.expression(), d + 1); }
        else if (n instanceof Ast.LiteralExpr x) line(d, "Literal " + (x.value() instanceof String ? quote((String) x.value()) : x.value()));
        else if (n instanceof Ast.NameExpr x) line(d, "Name " + x.name());
        else if (n instanceof Ast.UnaryExpr x) { line(d, "Unary " + x.operator()); node(x.operand(), d + 1); }
        else if (n instanceof Ast.BinaryExpr x) { line(d, "Binary " + x.operator()); node(x.left(), d + 1); node(x.right(), d + 1); }
        else if (n instanceof Ast.AssignExpr x) { line(d, "Assign " + x.operator()); node(x.target(), d + 1); node(x.value(), d + 1); }
        else if (n instanceof Ast.CallExpr x) { line(d, "Call"); node(x.callee(), d + 1); each(x.arguments(), d + 1); }
        else if (n instanceof Ast.IndexExpr x) { line(d, "Index"); node(x.target(), d + 1); node(x.index(), d + 1); }
        else if (n instanceof Ast.MemberExpr x) { line(d, "Member " + x.member()); node(x.target(), d + 1); }
        else if (n instanceof Ast.GroupExpr x) { line(d, "Group"); node(x.expression(), d + 1); }
        else if (n instanceof Ast.SizeofExpr x) { line(d, "Sizeof"); node(x.type(), d + 1); }
        else if (n instanceof Ast.CastExpr x) { line(d, "Cast"); node(x.expression(), d + 1); node(x.target(), d + 1); }
        else if (n instanceof Ast.ArrayLiteralExpr x) { line(d, "ArrayLiteral"); each(x.elements(), d + 1); }
        else if (n instanceof Ast.AggregateLiteralExpr x) { line(d, "AggregateLiteral " + x.typeName()); each(x.fields(), d + 1); }
        else if (n instanceof Ast.FieldInit x) { line(d, "FieldInit " + x.name()); node(x.value(), d + 1); }
        else throw new IllegalArgumentException("unknown AST node " + n.getClass());
    }
    private void each(List<? extends Ast.Node> nodes, int depth) { for (Ast.Node n : nodes) node(n, depth); }
    private static String quote(String value) { return '"' + value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").replace("\0", "\\0").replace("\"", "\\\"") + '"'; }
}
