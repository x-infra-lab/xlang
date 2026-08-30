package com.xlang.compiler.sema;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
import com.xlang.compiler.source.SourceSpan;
import com.xlang.compiler.token.TokenType;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** P2 lexical name resolution and type checking. */
public final class TypeChecker {
    private final Ast.Program program;
    private final boolean requireMain;
    private final SymbolTable globals = new SymbolTable();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<Ast.Expr, Type> expressionTypes = new IdentityHashMap<>();
    private final Map<Ast.FnDecl, Symbol.Function> functionSymbols = new IdentityHashMap<>();
    private final Map<Ast.LetDecl, Type> declaredGlobalTypes = new IdentityHashMap<>();
    private Type returnType = Type.VOID;
    private int loopDepth;

    public TypeChecker(Ast.Program program) {
        this(program, true);
    }

    public TypeChecker(Ast.Program program, boolean requireMain) {
        this.program = program;
        this.requireMain = requireMain;
    }

    public TypeCheckResult check() {
        declareTopLevelSymbols();
        installBuiltins();
        if (requireMain) checkMain();
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.LetDecl declaration) checkGlobal(declaration);
        }
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.FnDecl function) checkFunction(function);
        }
        return new TypeCheckResult(diagnostics, expressionTypes);
    }

    private void installBuiltins() {
        SourceSpan builtinSpan = new SourceSpan(0, 0, 1, 1);
        for (Map.Entry<String, BuiltinFunctions.Signature> entry
                : BuiltinFunctions.intrinsics().entrySet()) {
            Symbol existing = globals.lookupLocal(entry.getKey()).orElse(null);
            if (existing != null) {
                error(existing.span(), "compiler intrinsic '" + entry.getKey() + "' is reserved");
                continue;
            }
            BuiltinFunctions.Signature signature = entry.getValue();
            globals.define(new Symbol.Function(entry.getKey(), signature.parameters(),
                signature.result(), builtinSpan));
        }
        for (Map.Entry<String, BuiltinFunctions.Signature> entry
                : BuiltinFunctions.runtime().entrySet()) {
            if (globals.lookupLocal(entry.getKey()).isPresent()) continue;
            BuiltinFunctions.Signature signature = entry.getValue();
            globals.define(new Symbol.Function(entry.getKey(), signature.parameters(),
                signature.result(), builtinSpan));
        }
        if (!requireMain && globals.lookupLocal("main").isEmpty()) {
            globals.define(new Symbol.Function("main", List.of(), Type.INT, builtinSpan));
        }
    }

    private void declareTopLevelSymbols() {
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.FnDecl function) {
                List<Type> parameters = function.params().stream().map(p -> resolveType(p.type(), false)).toList();
                Type result = function.returnType() == null ? Type.VOID : resolveType(function.returnType(), true);
                Symbol.Function symbol = new Symbol.Function(function.name(), parameters, result, function.span());
                functionSymbols.put(function, symbol);
                define(globals, symbol, function.span());
            } else if (item instanceof Ast.LetDecl declaration && declaration.type() != null) {
                Type type = resolveType(declaration.type(), false);
                declaredGlobalTypes.put(declaration, type);
                define(globals, new Symbol.Variable(declaration.name(), type, declaration.span()), declaration.span());
            }
        }
    }

    private void checkMain() {
        var main = globals.lookupLocal("main");
        if (main.isEmpty()) {
            error(program.span(), "program must declare 'fn main() -> int'");
            return;
        }
        if (!(main.get() instanceof Symbol.Function function)
                || !function.parameterTypes().isEmpty() || function.returnType() != Type.INT) {
            error(main.get().span(), "entry point must have signature 'fn main() -> int'");
        }
    }

    private void checkGlobal(Ast.LetDecl declaration) {
        Type initializer = typeOf(declaration.initializer(), globals);
        Type declared = declaration.type() == null ? initializer : declaredGlobalTypes.get(declaration);
        if (declaration.type() != null) requireAssignable(declared, initializer, declaration.initializer().span(), "initializer");
        if (declaration.type() == null) {
            if (declared == Type.VOID) error(declaration.span(), "cannot infer variable type from void expression");
            define(globals, new Symbol.Variable(declaration.name(), declared, declaration.span()), declaration.span());
        }
    }

    private void checkFunction(Ast.FnDecl function) {
        Symbol.Function signature = functionSymbols.get(function);
        List<Type> parameterTypes = signature.parameterTypes();
        Type functionReturn = signature.returnType();
        SymbolTable scope = globals.child();
        for (int i = 0; i < function.params().size(); i++) {
            Ast.Param parameter = function.params().get(i);
            Type type = parameterTypes.get(i);
            define(scope, new Symbol.Variable(parameter.name(), type, parameter.span()), parameter.span());
        }
        Type previousReturn = returnType;
        returnType = functionReturn;
        checkBlock(function.body(), scope, false);
        if (returnType != Type.VOID && !guaranteesReturn(function.body())) {
            error(function.body().span(), "function '" + function.name() + "' may complete without returning " + returnType.displayName());
        }
        returnType = previousReturn;
    }

    private void checkBlock(Ast.Block block, SymbolTable enclosing, boolean createScope) {
        SymbolTable scope = createScope ? enclosing.child() : enclosing;
        for (Ast.Stmt statement : block.statements()) checkStatement(statement, scope);
    }

    private void checkStatement(Ast.Stmt statement, SymbolTable scope) {
        if (statement instanceof Ast.LetDecl declaration) {
            Type initializer = typeOf(declaration.initializer(), scope);
            Type declared = declaration.type() == null ? initializer : resolveType(declaration.type(), false);
            if (declaration.type() != null) requireAssignable(declared, initializer, declaration.initializer().span(), "initializer");
            if (declared == Type.VOID) error(declaration.span(), "variable cannot have type void");
            define(scope, new Symbol.Variable(declaration.name(), declared, declaration.span()), declaration.span());
        } else if (statement instanceof Ast.ReturnStmt returned) {
            Type actual = returned.value() == null ? Type.VOID : typeOf(returned.value(), scope);
            requireAssignable(returnType, actual, returned.span(), "return value");
        } else if (statement instanceof Ast.IfStmt conditional) {
            require(typeOf(conditional.condition(), scope), Type.BOOL, conditional.condition().span(), "if condition");
            checkBlock(conditional.thenBranch(), scope, true);
            if (conditional.elseBranch() instanceof Ast.Block block) checkBlock(block, scope, true);
            else if (conditional.elseBranch() != null) checkStatement(conditional.elseBranch(), scope);
        } else if (statement instanceof Ast.WhileStmt loop) {
            require(typeOf(loop.condition(), scope), Type.BOOL, loop.condition().span(), "while condition");
            loopDepth++;
            checkBlock(loop.body(), scope, true);
            loopDepth--;
        } else if (statement instanceof Ast.BreakStmt || statement instanceof Ast.ContinueStmt) {
            if (loopDepth == 0) error(statement.span(), "'break' and 'continue' are only valid inside a loop");
        } else if (statement instanceof Ast.Block block) {
            checkBlock(block, scope, true);
        } else if (statement instanceof Ast.ExprStmt expression) {
            typeOf(expression.expression(), scope);
        }
    }

    private Type typeOf(Ast.Expr expression, SymbolTable scope) {
        Type type;
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Long) type = Type.INT;
            else if (value instanceof Boolean) type = Type.BOOL;
            else if (value instanceof String) type = Type.STRING;
            else {
                error(expression.span(), "null requires pointer types, which are implemented in P9");
                type = Type.ERROR;
            }
        } else if (expression instanceof Ast.NameExpr name) {
            Symbol symbol = scope.lookup(name.name()).orElse(null);
            if (symbol instanceof Symbol.Variable variable) type = variable.type();
            else if (symbol instanceof Symbol.Function) {
                error(name.span(), "function '" + name.name() + "' can only be used as a call target");
                type = Type.ERROR;
            } else {
                error(name.span(), "undefined name '" + name.name() + "'");
                type = Type.ERROR;
            }
        } else if (expression instanceof Ast.GroupExpr group) {
            type = typeOf(group.expression(), scope);
        } else if (expression instanceof Ast.UnaryExpr unary) {
            type = checkUnary(unary, scope);
        } else if (expression instanceof Ast.BinaryExpr binary) {
            type = checkBinary(binary, scope);
        } else if (expression instanceof Ast.AssignExpr assignment) {
            type = checkAssignment(assignment, scope);
        } else if (expression instanceof Ast.CallExpr call) {
            type = checkCall(call, scope);
        } else if (expression instanceof Ast.IndexExpr index) {
            typeOf(index.target(), scope);
            typeOf(index.index(), scope);
            error(expression.span(), "array indexing is implemented in P9");
            type = Type.ERROR;
        } else if (expression instanceof Ast.MemberExpr member) {
            typeOf(member.target(), scope);
            error(expression.span(), "member access is implemented in P9");
            type = Type.ERROR;
        } else {
            throw new IllegalStateException("unknown expression " + expression.getClass());
        }
        expressionTypes.put(expression, type);
        return type;
    }

    private Type checkUnary(Ast.UnaryExpr unary, SymbolTable scope) {
        Type operand = typeOf(unary.operand(), scope);
        return switch (unary.operator()) {
            case MINUS -> requireAndReturn(operand, Type.INT, unary.span(), "unary '-'", Type.INT);
            case BANG -> requireAndReturn(operand, Type.BOOL, unary.span(), "unary '!'", Type.BOOL);
            case STAR, AMP -> {
                error(unary.span(), "pointer operators are implemented in P9");
                yield Type.ERROR;
            }
            default -> throw new IllegalStateException("unexpected unary operator " + unary.operator());
        };
    }

    private Type checkBinary(Ast.BinaryExpr binary, SymbolTable scope) {
        Type left = typeOf(binary.left(), scope);
        Type right = typeOf(binary.right(), scope);
        return switch (binary.operator()) {
            case PLUS, MINUS, STAR, SLASH, PERCENT -> binaryOperands(binary, left, right, Type.INT, Type.INT);
            case LT, LT_EQ, GT, GT_EQ -> binaryOperands(binary, left, right, Type.INT, Type.BOOL);
            case AMP_AMP, PIPE_PIPE -> binaryOperands(binary, left, right, Type.BOOL, Type.BOOL);
            case EQ_EQ, BANG_EQ -> {
                if (!compatible(left, right) || left == Type.VOID) {
                    error(binary.span(), "equality operands must have the same non-void type, got " + describe(left) + " and " + describe(right));
                }
                yield Type.BOOL;
            }
            default -> throw new IllegalStateException("unexpected binary operator " + binary.operator());
        };
    }

    private Type checkAssignment(Ast.AssignExpr assignment, SymbolTable scope) {
        Type target = assignableTargetType(assignment.target(), scope);
        Type value = typeOf(assignment.value(), scope);
        if (assignment.operator() == TokenType.ASSIGN) {
            requireAssignable(target, value, assignment.span(), "assignment");
            return target;
        }
        require(target, Type.INT, assignment.target().span(), "compound assignment target");
        require(value, Type.INT, assignment.value().span(), "compound assignment value");
        return Type.INT;
    }

    private Type assignableTargetType(Ast.Expr target, SymbolTable scope) {
        if (target instanceof Ast.NameExpr name) {
            Symbol symbol = scope.lookup(name.name()).orElse(null);
            if (symbol instanceof Symbol.Variable variable) {
                expressionTypes.put(target, variable.type());
                return variable.type();
            }
            if (symbol == null) error(name.span(), "undefined name '" + name.name() + "'");
            else error(name.span(), "cannot assign to function '" + name.name() + "'");
        } else {
            typeOf(target, scope);
            error(target.span(), "assignment target is not assignable");
        }
        return Type.ERROR;
    }

    private Type checkCall(Ast.CallExpr call, SymbolTable scope) {
        if (!(call.callee() instanceof Ast.NameExpr name)) {
            typeOf(call.callee(), scope);
            for (Ast.Expr argument : call.arguments()) typeOf(argument, scope);
            error(call.callee().span(), "call target must be a function name");
            return Type.ERROR;
        }
        Symbol symbol = scope.lookup(name.name()).orElse(null);
        if (!(symbol instanceof Symbol.Function function)) {
            if (symbol == null) error(name.span(), "undefined function '" + name.name() + "'");
            else error(name.span(), "'" + name.name() + "' is not a function");
            for (Ast.Expr argument : call.arguments()) typeOf(argument, scope);
            expressionTypes.put(name, Type.ERROR);
            return Type.ERROR;
        }
        expressionTypes.put(name, function.returnType());
        if (call.arguments().size() != function.parameterTypes().size()) {
            error(call.span(), "function '" + name.name() + "' expects " + function.parameterTypes().size()
                + " arguments but got " + call.arguments().size());
        }
        for (int i = 0; i < call.arguments().size(); i++) {
            Type actual = typeOf(call.arguments().get(i), scope);
            if (i < function.parameterTypes().size()) {
                requireAssignable(function.parameterTypes().get(i), actual, call.arguments().get(i).span(), "argument " + (i + 1));
            }
        }
        return function.returnType();
    }

    private Type binaryOperands(Ast.BinaryExpr expression, Type left, Type right,
                                Type required, Type result) {
        require(left, required, expression.left().span(), "left operand");
        require(right, required, expression.right().span(), "right operand");
        return result;
    }

    private Type requireAndReturn(Type actual, Type expected, SourceSpan span,
                                  String context, Type result) {
        require(actual, expected, span, context);
        return result;
    }

    private void require(Type actual, Type expected, SourceSpan span, String context) {
        if (!compatible(expected, actual)) {
            error(span, context + " requires " + expected.displayName() + " but got " + describe(actual));
        }
    }

    private void requireAssignable(Type expected, Type actual, SourceSpan span, String context) {
        if (!compatible(expected, actual)) {
            error(span, context + " expects " + expected.displayName() + " but got " + describe(actual));
        }
    }

    private static boolean compatible(Type expected, Type actual) {
        return expected == Type.ERROR || actual == Type.ERROR || expected == actual;
    }

    private Type resolveType(Ast.TypeRef reference, boolean allowVoid) {
        if (reference instanceof Ast.NamedType named) {
            Type type = switch (named.name()) {
                case "int" -> Type.INT;
                case "bool" -> Type.BOOL;
                case "void" -> Type.VOID;
                case "string" -> Type.STRING;
                default -> Type.ERROR;
            };
            if (type == Type.VOID && !allowVoid) error(reference.span(), "void is only valid as a function return type");
            return type;
        }
        error(reference.span(), "pointer and array types are implemented in P9");
        return Type.ERROR;
    }

    private void define(SymbolTable table, Symbol symbol, SourceSpan span) {
        if (!table.define(symbol)) error(span, "duplicate declaration of '" + symbol.name() + "' in the same scope");
    }

    private static boolean guaranteesReturn(Ast.Block block) {
        for (Ast.Stmt statement : block.statements()) {
            if (statement instanceof Ast.ReturnStmt) return true;
            if (statement instanceof Ast.Block nested && guaranteesReturn(nested)) return true;
            if (statement instanceof Ast.IfStmt conditional
                    && conditional.elseBranch() != null
                    && guaranteesReturn(conditional.thenBranch())
                    && statementReturns(conditional.elseBranch())) return true;
        }
        return false;
    }

    private static boolean statementReturns(Ast.Stmt statement) {
        if (statement instanceof Ast.ReturnStmt) return true;
        if (statement instanceof Ast.Block block) return guaranteesReturn(block);
        return statement instanceof Ast.IfStmt conditional
            && conditional.elseBranch() != null
            && guaranteesReturn(conditional.thenBranch())
            && statementReturns(conditional.elseBranch());
    }

    private static String describe(Type type) {
        return type.displayName();
    }

    private void error(SourceSpan span, String message) {
        diagnostics.add(Diagnostic.error(span, message));
    }
}
