package com.xlang.compiler.xir;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.sema.LayoutEngine;
import com.xlang.compiler.sema.Type;
import com.xlang.compiler.sema.TypeCheckResult;
import com.xlang.compiler.token.TokenType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lowers a successfully typed AST into explicit basic blocks and P9 memory operations. */
public final class Lowerer {
    private final Ast.Program program;
    private final TypeCheckResult types;
    private final LayoutEngine layouts = new LayoutEngine();
    private final Map<String, Binding> globals = new LinkedHashMap<>();

    public Lowerer(Ast.Program program, TypeCheckResult types) {
        this.program = program;
        this.types = types;
    }

    public Xir.Module lower() {
        List<Ast.LetDecl> globalDeclarations = new ArrayList<>();
        List<Xir.Global> globalList = new ArrayList<>();
        for (Ast.Item item : program.items()) {
            if (!(item instanceof Ast.LetDecl declaration)) continue;
            Type type = declaredType(declaration);
            Xir.Value scalar = type.aggregateValue() ? null
                : new Xir.Value("@" + declaration.name(), type);
            globals.put(declaration.name(), new Binding(scalar, type, declaration.name()));
            globalList.add(new Xir.Global(declaration.name(), type));
            globalDeclarations.add(declaration);
        }
        List<Xir.Function> functions = new ArrayList<>();
        if (!globalDeclarations.isEmpty()) functions.add(lowerModuleInitializer(globalDeclarations));
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.FnDecl function) functions.add(new FunctionLowerer(function).lower());
        }
        return new Xir.Module(globalList, functions);
    }

    private Type declaredType(Ast.LetDecl declaration) {
        return declaration.type() == null ? types.typeOf(declaration.initializer())
            : types.resolvedType(declaration.type());
    }

    private Xir.Function lowerModuleInitializer(List<Ast.LetDecl> declarations) {
        FunctionLowerer lowerer = new FunctionLowerer("$module_init", Type.VOID, List.of());
        for (Ast.LetDecl declaration : declarations) {
            Binding binding = globals.get(declaration.name());
            Xir.Value initializer = lowerer.expression(declaration.initializer());
            if (binding.type().aggregateValue()) {
                lowerer.emit(new Xir.MemCopy(lowerer.address(binding), initializer,
                    layouts.layout(binding.type()).size()));
            } else lowerer.emit(new Xir.Copy(binding.value(), initializer));
        }
        lowerer.terminate(new Xir.Return(null));
        return lowerer.finish();
    }

    private final class FunctionLowerer {
        private final String name;
        private final Type returnType;
        private final List<Xir.Value> parameters = new ArrayList<>();
        private final List<MutableBlock> blocks = new ArrayList<>();
        private final Deque<LoopTargets> loops = new ArrayDeque<>();
        private Scope scope;
        private MutableBlock current;
        private int temporaryCounter;
        private int localCounter;
        private int labelCounter;
        private final Ast.FnDecl declaration;

        FunctionLowerer(Ast.FnDecl declaration) {
            this.declaration = declaration;
            name = declaration.name();
            returnType = declaration.returnType() == null ? Type.VOID
                : types.resolvedType(declaration.returnType());
            scope = new Scope(null);
            current = block("entry");
            for (Ast.Param parameter : declaration.params()) {
                Type sourceType = types.resolvedType(parameter.type());
                Type abiType = sourceType.aggregateValue() ? Type.pointer(sourceType) : sourceType;
                Xir.Value value = local(parameter.name(), abiType);
                parameters.add(value);
                scope.define(parameter.name(), new Binding(value, sourceType, null));
            }
        }

        FunctionLowerer(String name, Type returnType, List<Xir.Value> parameters) {
            declaration = null;
            this.name = name;
            this.returnType = returnType;
            this.parameters.addAll(parameters);
            scope = new Scope(null);
            current = block("entry");
        }

        Xir.Function lower() {
            lowerBlock(declaration.body(), false);
            if (!terminated()) terminate(returnType == Type.VOID
                ? new Xir.Return(null) : new Xir.Unreachable());
            return finish();
        }
        Xir.Function finish() {
            return new Xir.Function(name, parameters, returnType,
                blocks.stream().map(MutableBlock::freeze).toList());
        }

        private void lowerBlock(Ast.Block block, boolean nestedScope) {
            Scope previous = scope;
            if (nestedScope) scope = new Scope(scope);
            for (Ast.Stmt statement : block.statements()) {
                if (terminated()) break;
                statement(statement);
            }
            scope = previous;
        }

        private void statement(Ast.Stmt statement) {
            if (statement instanceof Ast.LetDecl let) {
                Type sourceType = declaredType(let);
                Xir.Value initializer = expression(let.initializer());
                if (sourceType.aggregateValue()) {
                    Xir.Value storage = allocate(sourceType, let.name());
                    emit(new Xir.MemCopy(storage, initializer, layouts.layout(sourceType).size()));
                    scope.define(let.name(), new Binding(storage, sourceType, null));
                } else {
                    Xir.Value variable = local(let.name(), sourceType);
                    scope.define(let.name(), new Binding(variable, sourceType, null));
                    emit(new Xir.Copy(variable, initializer));
                }
            } else if (statement instanceof Ast.ReturnStmt returned) {
                terminate(new Xir.Return(returned.value() == null ? null : expression(returned.value())));
            } else if (statement instanceof Ast.IfStmt conditional) lowerIf(conditional);
            else if (statement instanceof Ast.WhileStmt loop) lowerWhile(loop);
            else if (statement instanceof Ast.BreakStmt) terminate(new Xir.Jump(loops.peek().breakLabel()));
            else if (statement instanceof Ast.ContinueStmt) terminate(new Xir.Jump(loops.peek().continueLabel()));
            else if (statement instanceof Ast.Block block) lowerBlock(block, true);
            else if (statement instanceof Ast.ExprStmt expression) expression(expression.expression());
        }

        private void lowerIf(Ast.IfStmt conditional) {
            Xir.Value condition = expression(conditional.condition());
            MutableBlock thenBlock = block(label("if.then"));
            MutableBlock elseBlock = block(label("if.else"));
            MutableBlock joinBlock = block(label("if.end"));
            terminate(new Xir.Branch(condition, thenBlock.label, elseBlock.label));
            current = thenBlock;
            lowerBlock(conditional.thenBranch(), true);
            jumpIfOpen(joinBlock.label);
            current = elseBlock;
            if (conditional.elseBranch() instanceof Ast.Block block) lowerBlock(block, true);
            else if (conditional.elseBranch() != null) statement(conditional.elseBranch());
            jumpIfOpen(joinBlock.label);
            current = joinBlock;
        }

        private void lowerWhile(Ast.WhileStmt loop) {
            MutableBlock conditionBlock = block(label("while.cond"));
            MutableBlock bodyBlock = block(label("while.body"));
            MutableBlock exitBlock = block(label("while.end"));
            terminate(new Xir.Jump(conditionBlock.label));
            current = conditionBlock;
            Xir.Value condition = expression(loop.condition());
            terminate(new Xir.Branch(condition, bodyBlock.label, exitBlock.label));
            loops.push(new LoopTargets(exitBlock.label, conditionBlock.label));
            current = bodyBlock;
            lowerBlock(loop.body(), true);
            jumpIfOpen(conditionBlock.label);
            loops.pop();
            current = exitBlock;
        }

        private Xir.Value expression(Ast.Expr expression) {
            if (expression instanceof Ast.LiteralExpr literal) {
                Type type = types.typeOf(expression);
                if (type == Type.NULL) type = Type.pointer(Type.VOID);
                Xir.Value result = temporary(type);
                emit(new Xir.Const(result, literal.value() == null ? 0L : literal.value()));
                return result;
            }
            if (expression instanceof Ast.NameExpr nameExpression) {
                Binding binding = binding(nameExpression.name());
                return binding.type().aggregateValue() ? address(binding) : binding.value();
            }
            if (expression instanceof Ast.GroupExpr group) return expression(group.expression());
            if (expression instanceof Ast.UnaryExpr unary) return unary(unary);
            if (expression instanceof Ast.BinaryExpr binary) {
                if (binary.operator() == TokenType.AMP_AMP || binary.operator() == TokenType.PIPE_PIPE) {
                    return shortCircuit(binary);
                }
                return binary(binary);
            }
            if (expression instanceof Ast.AssignExpr assignment) return assignment(assignment);
            if (expression instanceof Ast.CallExpr call) {
                String function = ((Ast.NameExpr) call.callee()).name();
                List<Xir.Value> arguments = call.arguments().stream().map(this::expression).toList();
                Type semantic = types.typeOf(expression);
                Xir.Value result = semantic == Type.VOID ? null : temporary(representation(semantic));
                emit(new Xir.Call(result, function, arguments));
                return result;
            }
            if (expression instanceof Ast.IndexExpr || expression instanceof Ast.MemberExpr) {
                Type semantic = types.typeOf(expression);
                Xir.Value address = lvalueAddress(expression);
                if (semantic.aggregateValue()) return address;
                Xir.Value result = temporary(semantic);
                emit(new Xir.Load(result, address));
                return result;
            }
            if (expression instanceof Ast.SizeofExpr measured) {
                Xir.Value result = temporary(Type.INT);
                emit(new Xir.Const(result, layouts.layout(types.resolvedType(measured.type())).size()));
                return result;
            }
            if (expression instanceof Ast.CastExpr cast) {
                Xir.Value source = expression(cast.expression());
                Xir.Value result = temporary(representation(types.typeOf(cast)));
                emit(new Xir.Copy(result, source));
                return result;
            }
            if (expression instanceof Ast.ArrayLiteralExpr array) return arrayLiteral(array);
            if (expression instanceof Ast.AggregateLiteralExpr aggregate) return aggregateLiteral(aggregate);
            throw new IllegalStateException("checked expression cannot be lowered: " + expression.getClass());
        }

        private Xir.Value unary(Ast.UnaryExpr unary) {
            if (unary.operator() == TokenType.AMP) return lvalueAddress(unary.operand());
            Xir.Value operand = expression(unary.operand());
            if (unary.operator() == TokenType.STAR) {
                Type resultType = types.typeOf(unary);
                if (resultType.aggregateValue()) return operand;
                Xir.Value result = temporary(resultType);
                emit(new Xir.Load(result, operand));
                return result;
            }
            Xir.Value result = temporary(types.typeOf(unary));
            emit(new Xir.Unary(result, unary.operator(), operand));
            return result;
        }

        private Xir.Value binary(Ast.BinaryExpr binary) {
            Xir.Value left = expression(binary.left());
            Xir.Value right = expression(binary.right());
            Type leftType = types.typeOf(binary.left());
            Type rightType = types.typeOf(binary.right());
            Type resultType = types.typeOf(binary);
            if ((binary.operator() == TokenType.PLUS || binary.operator() == TokenType.MINUS)
                    && (leftType instanceof Type.Pointer || rightType instanceof Type.Pointer)) {
                if (leftType instanceof Type.Pointer pointer && rightType == Type.INT) {
                    right = scaled(right, pointer.target());
                } else if (rightType instanceof Type.Pointer pointer && leftType == Type.INT) {
                    left = scaled(left, pointer.target());
                }
            }
            Xir.Value result = temporary(representation(resultType));
            emit(new Xir.Binary(result, binary.operator(), left, right));
            if (binary.operator() == TokenType.MINUS && leftType instanceof Type.Pointer pointer
                    && rightType instanceof Type.Pointer) {
                long scale = elementSize(pointer.target());
                if (scale != 1) {
                    Xir.Value divisor = constant(scale);
                    Xir.Value divided = temporary(Type.INT);
                    emit(new Xir.Binary(divided, TokenType.SLASH, result, divisor));
                    return divided;
                }
            }
            return result;
        }

        private Xir.Value scaled(Xir.Value index, Type element) {
            long scale = elementSize(element);
            if (scale == 1) return index;
            Xir.Value result = temporary(Type.INT);
            emit(new Xir.Binary(result, TokenType.STAR, index, constant(scale)));
            return result;
        }

        private long elementSize(Type element) {
            return element == Type.VOID ? 1 : layouts.layout(element).size();
        }

        private Xir.Value assignment(Ast.AssignExpr assignment) {
            Type targetType = types.typeOf(assignment.target());
            Xir.Value value = expression(assignment.value());
            if (assignment.operator() != TokenType.ASSIGN) {
                Xir.Value currentValue = expression(assignment.target());
                Xir.Value combined = temporary(Type.INT);
                emit(new Xir.Binary(combined, compoundOperator(assignment.operator()), currentValue, value));
                value = combined;
            }
            if (assignment.target() instanceof Ast.NameExpr name && !targetType.aggregateValue()) {
                Binding target = binding(name.name());
                emit(new Xir.Copy(target.value(), value));
                return target.value();
            }
            Xir.Value targetAddress = lvalueAddress(assignment.target());
            if (targetType.aggregateValue()) emit(new Xir.MemCopy(targetAddress, value,
                layouts.layout(targetType).size()));
            else emit(new Xir.Store(targetAddress, value));
            return value;
        }

        private Xir.Value lvalueAddress(Ast.Expr expression) {
            if (expression instanceof Ast.GroupExpr group) return lvalueAddress(group.expression());
            if (expression instanceof Ast.NameExpr name) return address(binding(name.name()));
            if (expression instanceof Ast.UnaryExpr unary && unary.operator() == TokenType.STAR) {
                return expression(unary.operand());
            }
            if (expression instanceof Ast.IndexExpr index) {
                Type targetType = types.typeOf(index.target());
                Type element = targetType instanceof Type.Array array ? array.element()
                    : ((Type.Pointer) targetType).target();
                Xir.Value base = expression(index.target());
                Xir.Value bytes = scaled(expression(index.index()), element);
                Xir.Value result = temporary(Type.pointer(element));
                emit(new Xir.PointerOffset(result, base, bytes));
                return result;
            }
            if (expression instanceof Ast.MemberExpr member) {
                Type targetType = types.typeOf(member.target());
                Type.Aggregate aggregate = targetType instanceof Type.Pointer pointer
                    ? (Type.Aggregate) pointer.target() : (Type.Aggregate) targetType;
                Xir.Value base = expression(member.target());
                Type fieldType = aggregate.field(member.member()).type();
                Xir.Value result = temporary(Type.pointer(fieldType));
                emit(new Xir.PointerOffset(result, base,
                    constant(layouts.fieldOffset(aggregate, member.member()))));
                return result;
            }
            throw new IllegalStateException("checked lvalue cannot be lowered: " + expression.getClass());
        }

        private Xir.Value arrayLiteral(Ast.ArrayLiteralExpr literal) {
            Type.Array type = (Type.Array) types.typeOf(literal);
            Xir.Value storage = allocate(type, "array");
            long stride = layouts.layout(type.element()).size();
            for (int index = 0; index < literal.elements().size(); index++) {
                Xir.Value address = temporary(Type.pointer(type.element()));
                emit(new Xir.PointerOffset(address, storage, constant(index * stride)));
                Xir.Value value = expression(literal.elements().get(index));
                if (type.element().aggregateValue()) emit(new Xir.MemCopy(address, value, stride));
                else emit(new Xir.Store(address, value));
            }
            return storage;
        }

        private Xir.Value aggregateLiteral(Ast.AggregateLiteralExpr literal) {
            Type.Aggregate type = (Type.Aggregate) types.typeOf(literal);
            Xir.Value storage = allocate(type, type.name());
            for (Ast.FieldInit initializer : literal.fields()) {
                Type fieldType = type.field(initializer.name()).type();
                Xir.Value address = temporary(Type.pointer(fieldType));
                emit(new Xir.PointerOffset(address, storage,
                    constant(layouts.fieldOffset(type, initializer.name()))));
                Xir.Value value = expression(initializer.value());
                if (fieldType.aggregateValue()) emit(new Xir.MemCopy(address, value,
                    layouts.layout(fieldType).size()));
                else emit(new Xir.Store(address, value));
            }
            return storage;
        }

        private Xir.Value shortCircuit(Ast.BinaryExpr binary) {
            Xir.Value result = temporary(Type.BOOL);
            Xir.Value left = expression(binary.left());
            MutableBlock rhsBlock = block(label("logic.rhs"));
            MutableBlock constantBlock = block(label("logic.short"));
            MutableBlock endBlock = block(label("logic.end"));
            if (binary.operator() == TokenType.AMP_AMP) {
                terminate(new Xir.Branch(left, rhsBlock.label, constantBlock.label));
            } else terminate(new Xir.Branch(left, constantBlock.label, rhsBlock.label));
            current = constantBlock;
            Xir.Value constant = temporary(Type.BOOL);
            emit(new Xir.Const(constant, binary.operator() == TokenType.PIPE_PIPE));
            emit(new Xir.Copy(result, constant));
            terminate(new Xir.Jump(endBlock.label));
            current = rhsBlock;
            emit(new Xir.Copy(result, expression(binary.right())));
            terminate(new Xir.Jump(endBlock.label));
            current = endBlock;
            return result;
        }

        private Xir.Value allocate(Type type, String hint) {
            Xir.Value result = temporary(Type.pointer(type));
            emit(new Xir.Allocate(result, type));
            return result;
        }
        private Xir.Value constant(long value) {
            Xir.Value result = temporary(Type.INT);
            emit(new Xir.Const(result, value));
            return result;
        }
        private Xir.Value address(Binding binding) {
            if (binding.type().aggregateValue() && binding.globalName() == null) return binding.value();
            Xir.Value result = temporary(Type.pointer(binding.type()));
            if (binding.globalName() != null) emit(new Xir.GlobalAddress(result, binding.globalName()));
            else emit(new Xir.AddressOf(result, binding.value()));
            return result;
        }
        private Binding binding(String name) {
            Binding local = scope.lookup(name);
            return local == null ? globals.get(name) : local;
        }
        private Xir.Value temporary(Type type) { return new Xir.Value("%t" + temporaryCounter++, type); }
        private Xir.Value local(String sourceName, Type type) {
            return new Xir.Value("%" + sourceName + "." + localCounter++, type);
        }
        private MutableBlock block(String blockLabel) {
            MutableBlock result = new MutableBlock(blockLabel);
            blocks.add(result);
            return result;
        }
        private String label(String prefix) { return prefix + "." + labelCounter++; }
        private void emit(Xir.Instruction instruction) {
            if (terminated()) throw new IllegalStateException("instruction emitted after terminator");
            current.instructions.add(instruction);
        }
        private void terminate(Xir.Terminator terminator) {
            if (terminated()) throw new IllegalStateException("basic block already terminated");
            current.terminator = terminator;
        }
        private void jumpIfOpen(String target) { if (!terminated()) terminate(new Xir.Jump(target)); }
        private boolean terminated() { return current.terminator != null; }
    }

    private static Type representation(Type semantic) {
        if (semantic.aggregateValue()) return Type.pointer(semantic);
        return semantic == Type.NULL ? Type.pointer(Type.VOID) : semantic;
    }
    private static TokenType compoundOperator(TokenType operator) {
        return switch (operator) {
            case PLUS_ASSIGN -> TokenType.PLUS;
            case MINUS_ASSIGN -> TokenType.MINUS;
            case STAR_ASSIGN -> TokenType.STAR;
            case SLASH_ASSIGN -> TokenType.SLASH;
            case PERCENT_ASSIGN -> TokenType.PERCENT;
            default -> throw new IllegalArgumentException("not a compound assignment: " + operator);
        };
    }
    private static final class MutableBlock {
        private final String label;
        private final List<Xir.Instruction> instructions = new ArrayList<>();
        private Xir.Terminator terminator;
        MutableBlock(String label) { this.label = label; }
        Xir.BasicBlock freeze() {
            if (terminator == null) throw new IllegalStateException("unterminated block " + label);
            return new Xir.BasicBlock(label, instructions, terminator);
        }
    }
    private static final class Scope {
        private final Scope parent;
        private final Map<String, Binding> values = new LinkedHashMap<>();
        Scope(Scope parent) { this.parent = parent; }
        void define(String name, Binding value) { values.put(name, value); }
        Binding lookup(String name) {
            Binding value = values.get(name);
            return value != null || parent == null ? value : parent.lookup(name);
        }
    }
    private record Binding(Xir.Value value, Type type, String globalName) {}
    private record LoopTargets(String breakLabel, String continueLabel) {}
}
