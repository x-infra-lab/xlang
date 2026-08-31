package com.xlang.compiler.sema;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.diag.Diagnostic;
import com.xlang.compiler.source.SourceSpan;
import com.xlang.compiler.token.TokenType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lexical name resolution and P9 type checking. */
public final class TypeChecker {
    private final Ast.Program program;
    private final boolean requireMain;
    private final SymbolTable globals = new SymbolTable();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<Ast.Expr, Type> expressionTypes = new IdentityHashMap<>();
    private final Map<Ast.TypeRef, Type> resolvedTypes = new IdentityHashMap<>();
    private final Map<String, Type.Aggregate> aggregates = new LinkedHashMap<>();
    private final Map<Ast.FnDecl, Symbol.Function> functionSymbols = new IdentityHashMap<>();
    private final Map<Ast.LetDecl, Type> declaredGlobalTypes = new IdentityHashMap<>();
    private Type returnType = Type.VOID;
    private int loopDepth;

    public TypeChecker(Ast.Program program) { this(program, true); }
    public TypeChecker(Ast.Program program, boolean requireMain) {
        this.program = program;
        this.requireMain = requireMain;
    }

    public TypeCheckResult check() {
        declareAggregateNames();
        defineAggregates();
        declareTopLevelSymbols();
        installBuiltins();
        if (requireMain) checkMain();
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.LetDecl declaration) checkGlobal(declaration);
        }
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.FnDecl function) checkFunction(function);
        }
        return new TypeCheckResult(diagnostics, expressionTypes, resolvedTypes, aggregates);
    }

    private void declareAggregateNames() {
        for (Ast.Item item : program.items()) {
            if (!(item instanceof Ast.AggregateDecl declaration)) continue;
            Type.AggregateKind kind = declaration.kind() == Ast.AggregateKind.STRUCT
                ? Type.AggregateKind.STRUCT : Type.AggregateKind.UNION;
            if (aggregates.putIfAbsent(declaration.name(), new Type.Aggregate(kind,
                    declaration.name())) != null) {
                error(declaration.span(), "duplicate aggregate declaration '" + declaration.name() + "'");
            }
        }
    }

    private void defineAggregates() {
        Set<String> defined = new HashSet<>();
        for (Ast.Item item : program.items()) {
            if (!(item instanceof Ast.AggregateDecl declaration) || !defined.add(declaration.name())) continue;
            Type.Aggregate aggregate = aggregates.get(declaration.name());
            List<Type.Field> fields = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Ast.FieldDecl field : declaration.fields()) {
                if (!names.add(field.name())) {
                    error(field.span(), "duplicate field '" + field.name() + "' in " + declaration.name());
                    continue;
                }
                Type type = resolveType(field.type(), false);
                fields.add(new Type.Field(field.name(), type));
            }
            if (fields.isEmpty()) error(declaration.span(), "aggregate '" + declaration.name() + "' must declare at least one field");
            aggregate.define(fields);
        }
        LayoutEngine layouts = new LayoutEngine();
        for (Type.Aggregate aggregate : aggregates.values()) {
            try { layouts.layout(aggregate); }
            catch (IllegalArgumentException exception) { error(program.span(), exception.getMessage()); }
        }
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
                List<Type> parameters = function.params().stream()
                    .map(parameter -> resolveType(parameter.type(), false)).toList();
                Type result = function.returnType() == null ? Type.VOID
                    : resolveType(function.returnType(), true);
                if (result.aggregateValue()) error(function.returnType().span(),
                    "aggregate return types must use a pointer");
                Symbol.Function symbol = new Symbol.Function(function.name(), parameters, result,
                    function.span());
                functionSymbols.put(function, symbol);
                define(globals, symbol, function.span());
            } else if (item instanceof Ast.LetDecl declaration && declaration.type() != null) {
                Type type = resolveType(declaration.type(), false);
                declaredGlobalTypes.put(declaration, type);
                define(globals, new Symbol.Variable(declaration.name(), type, declaration.span()),
                    declaration.span());
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
        if (declaration.type() != null) requireAssignable(declared, initializer,
            declaration.initializer().span(), "initializer");
        if (declaration.type() == null) {
            if (declared == Type.VOID || declared == Type.NULL) {
                error(declaration.span(), "cannot infer variable type from " + declared.displayName());
            }
            define(globals, new Symbol.Variable(declaration.name(), declared, declaration.span()),
                declaration.span());
        }
    }

    private void checkFunction(Ast.FnDecl function) {
        Symbol.Function signature = functionSymbols.get(function);
        SymbolTable scope = globals.child();
        for (int i = 0; i < function.params().size(); i++) {
            Ast.Param parameter = function.params().get(i);
            define(scope, new Symbol.Variable(parameter.name(), signature.parameterTypes().get(i),
                parameter.span()), parameter.span());
        }
        Type previousReturn = returnType;
        returnType = signature.returnType();
        checkBlock(function.body(), scope, false);
        if (returnType != Type.VOID && !guaranteesReturn(function.body())) {
            error(function.body().span(), "function '" + function.name()
                + "' may complete without returning " + returnType.displayName());
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
            if (declaration.type() != null) requireAssignable(declared, initializer,
                declaration.initializer().span(), "initializer");
            if (declared == Type.VOID || declared == Type.NULL) {
                error(declaration.span(), "variable cannot have type " + declared.displayName());
            }
            define(scope, new Symbol.Variable(declaration.name(), declared, declaration.span()),
                declaration.span());
        } else if (statement instanceof Ast.ReturnStmt returned) {
            Type actual = returned.value() == null ? Type.VOID : typeOf(returned.value(), scope);
            requireAssignable(returnType, actual, returned.span(), "return value");
        } else if (statement instanceof Ast.IfStmt conditional) {
            require(typeOf(conditional.condition(), scope), Type.BOOL,
                conditional.condition().span(), "if condition");
            checkBlock(conditional.thenBranch(), scope, true);
            if (conditional.elseBranch() instanceof Ast.Block block) checkBlock(block, scope, true);
            else if (conditional.elseBranch() != null) checkStatement(conditional.elseBranch(), scope);
        } else if (statement instanceof Ast.WhileStmt loop) {
            require(typeOf(loop.condition(), scope), Type.BOOL, loop.condition().span(),
                "while condition");
            loopDepth++;
            checkBlock(loop.body(), scope, true);
            loopDepth--;
        } else if (statement instanceof Ast.BreakStmt || statement instanceof Ast.ContinueStmt) {
            if (loopDepth == 0) error(statement.span(),
                "'break' and 'continue' are only valid inside a loop");
        } else if (statement instanceof Ast.Block block) checkBlock(block, scope, true);
        else if (statement instanceof Ast.ExprStmt expression) typeOf(expression.expression(), scope);
    }

    private Type typeOf(Ast.Expr expression, SymbolTable scope) {
        Type type;
        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof Long) type = Type.INT;
            else if (value instanceof Boolean) type = Type.BOOL;
            else if (value instanceof String) type = Type.STRING;
            else type = Type.NULL;
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
        } else if (expression instanceof Ast.GroupExpr group) type = typeOf(group.expression(), scope);
        else if (expression instanceof Ast.UnaryExpr unary) type = checkUnary(unary, scope);
        else if (expression instanceof Ast.BinaryExpr binary) type = checkBinary(binary, scope);
        else if (expression instanceof Ast.AssignExpr assignment) type = checkAssignment(assignment, scope);
        else if (expression instanceof Ast.CallExpr call) type = checkCall(call, scope);
        else if (expression instanceof Ast.IndexExpr index) type = checkIndex(index, scope);
        else if (expression instanceof Ast.MemberExpr member) type = checkMember(member, scope);
        else if (expression instanceof Ast.SizeofExpr measured) {
            Type measuredType = resolveType(measured.type(), false);
            if (measuredType != Type.ERROR) validateLayout(measuredType, measured.span());
            type = Type.INT;
        } else if (expression instanceof Ast.CastExpr cast) type = checkCast(cast, scope);
        else if (expression instanceof Ast.ArrayLiteralExpr array) type = checkArrayLiteral(array, scope);
        else if (expression instanceof Ast.AggregateLiteralExpr aggregate) {
            type = checkAggregateLiteral(aggregate, scope);
        } else throw new IllegalStateException("unknown expression " + expression.getClass());
        expressionTypes.put(expression, type);
        return type;
    }

    private Type checkUnary(Ast.UnaryExpr unary, SymbolTable scope) {
        Type operand = typeOf(unary.operand(), scope);
        return switch (unary.operator()) {
            case MINUS -> requireAndReturn(operand, Type.INT, unary.span(), "unary '-'", Type.INT);
            case BANG -> requireAndReturn(operand, Type.BOOL, unary.span(), "unary '!'", Type.BOOL);
            case AMP -> {
                assignableTargetType(unary.operand(), scope);
                yield operand == Type.ERROR ? Type.ERROR : Type.pointer(operand);
            }
            case STAR -> {
                if (operand instanceof Type.Pointer pointer && pointer.target() != Type.VOID) {
                    yield pointer.target();
                }
                if (operand instanceof Type.Pointer) {
                    error(unary.span(), "cannot dereference *void without casting it to a concrete pointer");
                    yield Type.ERROR;
                }
                if (operand != Type.ERROR) error(unary.span(), "unary '*' requires a pointer but got " + describe(operand));
                yield Type.ERROR;
            }
            default -> throw new IllegalStateException("unexpected unary operator " + unary.operator());
        };
    }

    private Type checkBinary(Ast.BinaryExpr binary, SymbolTable scope) {
        Type left = typeOf(binary.left(), scope);
        Type right = typeOf(binary.right(), scope);
        return switch (binary.operator()) {
            case PLUS -> pointerAddition(binary, left, right);
            case MINUS -> pointerSubtraction(binary, left, right);
            case STAR, SLASH, PERCENT -> binaryOperands(binary, left, right, Type.INT, Type.INT);
            case LT, LT_EQ, GT, GT_EQ -> binaryOperands(binary, left, right, Type.INT, Type.BOOL);
            case AMP_AMP, PIPE_PIPE -> binaryOperands(binary, left, right, Type.BOOL, Type.BOOL);
            case EQ_EQ, BANG_EQ -> {
                if (!compatible(left, right) || left == Type.VOID || left.aggregateValue()) {
                    error(binary.span(), "equality operands must have compatible scalar types, got "
                        + describe(left) + " and " + describe(right));
                }
                yield Type.BOOL;
            }
            default -> throw new IllegalStateException("unexpected binary operator " + binary.operator());
        };
    }

    private Type pointerAddition(Ast.BinaryExpr expression, Type left, Type right) {
        if (!(left instanceof Type.Pointer) && !(right instanceof Type.Pointer)) {
            return binaryOperands(expression, left, right, Type.INT, Type.INT);
        }
        if (left instanceof Type.Pointer && right == Type.INT) return left;
        if (right instanceof Type.Pointer && left == Type.INT) return right;
        if (left != Type.ERROR && right != Type.ERROR) error(expression.span(),
            "'+' requires integers or one pointer and one integer");
        return Type.ERROR;
    }

    private Type pointerSubtraction(Ast.BinaryExpr expression, Type left, Type right) {
        if (!(left instanceof Type.Pointer) && !(right instanceof Type.Pointer)) {
            return binaryOperands(expression, left, right, Type.INT, Type.INT);
        }
        if (left instanceof Type.Pointer && right == Type.INT) return left;
        if (left instanceof Type.Pointer lp && right instanceof Type.Pointer rp
                && compatible(lp, rp)) return Type.INT;
        if (left != Type.ERROR && right != Type.ERROR) error(expression.span(),
            "'-' requires integers, pointer-minus-integer, or compatible pointers");
        return Type.ERROR;
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
        Type type;
        if (target instanceof Ast.NameExpr name) {
            Symbol symbol = scope.lookup(name.name()).orElse(null);
            if (symbol instanceof Symbol.Variable variable) type = variable.type();
            else {
                if (symbol == null) error(name.span(), "undefined name '" + name.name() + "'");
                else error(name.span(), "cannot assign to function '" + name.name() + "'");
                type = Type.ERROR;
            }
        } else if (target instanceof Ast.UnaryExpr unary && unary.operator() == TokenType.STAR
                || target instanceof Ast.IndexExpr || target instanceof Ast.MemberExpr) {
            type = typeOf(target, scope);
        } else {
            typeOf(target, scope);
            error(target.span(), "assignment target is not addressable");
            type = Type.ERROR;
        }
        expressionTypes.put(target, type);
        return type;
    }

    private Type checkIndex(Ast.IndexExpr index, SymbolTable scope) {
        Type target = typeOf(index.target(), scope);
        require(typeOf(index.index(), scope), Type.INT, index.index().span(), "array index");
        if (target instanceof Type.Array array) return array.element();
        if (target instanceof Type.Pointer pointer && pointer.target() != Type.VOID) return pointer.target();
        if (target instanceof Type.Pointer) {
            error(index.target().span(), "cannot index *void without casting it to a concrete pointer");
            return Type.ERROR;
        }
        if (target != Type.ERROR) error(index.target().span(),
            "index target must be an array or pointer, got " + describe(target));
        return Type.ERROR;
    }

    private Type checkMember(Ast.MemberExpr member, SymbolTable scope) {
        Type target = typeOf(member.target(), scope);
        if (target instanceof Type.Pointer pointer) target = pointer.target();
        if (!(target instanceof Type.Aggregate aggregate)) {
            if (target != Type.ERROR) error(member.target().span(),
                "member target must be a struct, union, or pointer to one");
            return Type.ERROR;
        }
        Type.Field field = aggregate.field(member.member());
        if (field == null) {
            error(member.span(), "type '" + aggregate.name() + "' has no field '" + member.member() + "'");
            return Type.ERROR;
        }
        return field.type();
    }

    private Type checkCast(Ast.CastExpr cast, SymbolTable scope) {
        Type source = typeOf(cast.expression(), scope);
        Type target = resolveType(cast.target(), false);
        boolean valid = compatible(source, target)
            || source == Type.INT && target instanceof Type.Pointer
            || target == Type.INT && source instanceof Type.Pointer
            || source instanceof Type.Pointer && target instanceof Type.Pointer;
        if (!valid && source != Type.ERROR && target != Type.ERROR) {
            error(cast.span(), "cannot cast " + source.displayName() + " to " + target.displayName());
        }
        return valid ? target : Type.ERROR;
    }

    private Type checkArrayLiteral(Ast.ArrayLiteralExpr array, SymbolTable scope) {
        if (array.elements().isEmpty()) {
            error(array.span(), "cannot infer the type of an empty array literal");
            return Type.ERROR;
        }
        Type element = typeOf(array.elements().get(0), scope);
        for (int index = 1; index < array.elements().size(); index++) {
            Type actual = typeOf(array.elements().get(index), scope);
            requireAssignable(element, actual, array.elements().get(index).span(), "array element");
        }
        return element == Type.ERROR ? Type.ERROR : Type.array(array.elements().size(), element);
    }

    private Type checkAggregateLiteral(Ast.AggregateLiteralExpr literal, SymbolTable scope) {
        Type.Aggregate aggregate = aggregates.get(literal.typeName());
        if (aggregate == null) {
            error(literal.span(), "unknown aggregate type '" + literal.typeName() + "'");
            literal.fields().forEach(field -> typeOf(field.value(), scope));
            return Type.ERROR;
        }
        Set<String> initialized = new HashSet<>();
        for (Ast.FieldInit initializer : literal.fields()) {
            if (!initialized.add(initializer.name())) {
                error(initializer.span(), "field '" + initializer.name() + "' initialized more than once");
            }
            Type.Field field = aggregate.field(initializer.name());
            Type actual = typeOf(initializer.value(), scope);
            if (field == null) error(initializer.span(), "type '" + aggregate.name()
                + "' has no field '" + initializer.name() + "'");
            else requireAssignable(field.type(), actual, initializer.value().span(),
                "field '" + initializer.name() + "'");
        }
        if (aggregate.kind() == Type.AggregateKind.STRUCT) {
            for (Type.Field field : aggregate.fields()) {
                if (!initialized.contains(field.name())) error(literal.span(),
                    "struct literal is missing field '" + field.name() + "'");
            }
        } else if (initialized.size() != 1) {
            error(literal.span(), "union literal must initialize exactly one field");
        }
        return aggregate;
    }

    private Type checkCall(Ast.CallExpr call, SymbolTable scope) {
        if (!(call.callee() instanceof Ast.NameExpr name)) {
            typeOf(call.callee(), scope);
            call.arguments().forEach(argument -> typeOf(argument, scope));
            error(call.callee().span(), "call target must be a function name");
            return Type.ERROR;
        }
        Symbol symbol = scope.lookup(name.name()).orElse(null);
        if (!(symbol instanceof Symbol.Function function)) {
            if (symbol == null) error(name.span(), "undefined function '" + name.name() + "'");
            else error(name.span(), "'" + name.name() + "' is not a function");
            call.arguments().forEach(argument -> typeOf(argument, scope));
            expressionTypes.put(name, Type.ERROR);
            return Type.ERROR;
        }
        expressionTypes.put(name, function.returnType());
        if (call.arguments().size() != function.parameterTypes().size()) {
            error(call.span(), "function '" + name.name() + "' expects "
                + function.parameterTypes().size() + " arguments but got " + call.arguments().size());
        }
        for (int index = 0; index < call.arguments().size(); index++) {
            Type actual = typeOf(call.arguments().get(index), scope);
            if (index < function.parameterTypes().size()) requireAssignable(
                function.parameterTypes().get(index), actual, call.arguments().get(index).span(),
                "argument " + (index + 1));
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
        if (!compatible(expected, actual)) error(span, context + " requires "
            + expected.displayName() + " but got " + describe(actual));
    }
    private void requireAssignable(Type expected, Type actual, SourceSpan span, String context) {
        if (!compatible(expected, actual)) error(span, context + " expects "
            + expected.displayName() + " but got " + describe(actual));
    }

    private static boolean compatible(Type expected, Type actual) {
        if (expected == Type.ERROR || actual == Type.ERROR || expected.equals(actual)) return true;
        if (actual == Type.NULL && expected instanceof Type.Pointer) return true;
        if (expected instanceof Type.Pointer left && actual instanceof Type.Pointer right) {
            return left.target() == Type.VOID || right.target() == Type.VOID
                || compatible(left.target(), right.target());
        }
        return false;
    }

    private Type resolveType(Ast.TypeRef reference, boolean allowVoid) {
        Type type;
        if (reference instanceof Ast.NamedType named) {
            type = switch (named.name()) {
                case "int" -> Type.INT;
                case "bool" -> Type.BOOL;
                case "void" -> Type.VOID;
                case "string" -> Type.STRING;
                default -> aggregates.getOrDefault(named.name(), null);
            };
            if (type == null) {
                error(reference.span(), "unknown type '" + named.name() + "'");
                type = Type.ERROR;
            }
        } else if (reference instanceof Ast.PointerType pointer) {
            type = Type.pointer(resolveType(pointer.target(), true));
        } else if (reference instanceof Ast.ArrayType array) {
            Type element = resolveType(array.element(), false);
            if (array.length() <= 0 || array.length() > Integer.MAX_VALUE) {
                error(array.span(), "array length must be between 1 and " + Integer.MAX_VALUE);
                type = Type.ERROR;
            } else type = Type.array(array.length(), element);
        } else throw new IllegalStateException("unknown type reference " + reference.getClass());
        if (type == Type.VOID && !allowVoid) error(reference.span(),
            "void is only valid as a function return or pointer target");
        resolvedTypes.put(reference, type);
        return type;
    }

    private void validateLayout(Type type, SourceSpan span) {
        try { new LayoutEngine().layout(type); }
        catch (IllegalArgumentException exception) { error(span, exception.getMessage()); }
    }
    private void define(SymbolTable table, Symbol symbol, SourceSpan span) {
        if (!table.define(symbol)) error(span,
            "duplicate declaration of '" + symbol.name() + "' in the same scope");
    }
    private static boolean guaranteesReturn(Ast.Block block) {
        for (Ast.Stmt statement : block.statements()) {
            if (statement instanceof Ast.ReturnStmt) return true;
            if (statement instanceof Ast.Block nested && guaranteesReturn(nested)) return true;
            if (statement instanceof Ast.IfStmt conditional && conditional.elseBranch() != null
                    && guaranteesReturn(conditional.thenBranch())
                    && statementReturns(conditional.elseBranch())) return true;
        }
        return false;
    }
    private static boolean statementReturns(Ast.Stmt statement) {
        if (statement instanceof Ast.ReturnStmt) return true;
        if (statement instanceof Ast.Block block) return guaranteesReturn(block);
        return statement instanceof Ast.IfStmt conditional && conditional.elseBranch() != null
            && guaranteesReturn(conditional.thenBranch())
            && statementReturns(conditional.elseBranch());
    }
    private static String describe(Type type) { return type.displayName(); }
    private void error(SourceSpan span, String message) { diagnostics.add(Diagnostic.error(span, message)); }
}
