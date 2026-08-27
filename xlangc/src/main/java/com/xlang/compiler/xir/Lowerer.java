package com.xlang.compiler.xir;

import com.xlang.compiler.ast.Ast;
import com.xlang.compiler.sema.Type;
import com.xlang.compiler.sema.TypeCheckResult;
import com.xlang.compiler.token.TokenType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lowers a successfully typed P2 AST into explicit P3 basic blocks. */
public final class Lowerer {
    private final Ast.Program program;
    private final TypeCheckResult types;
    private final Map<String, Xir.Value> globals = new LinkedHashMap<>();

    public Lowerer(Ast.Program program, TypeCheckResult types) {
        this.program = program;
        this.types = types;
    }

    public Xir.Module lower() {
        List<Ast.LetDecl> globalDeclarations = new ArrayList<>();
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.LetDecl declaration) {
                Type type = types.typeOf(declaration.initializer());
                globals.put(declaration.name(), new Xir.Value("@" + declaration.name(), type));
                globalDeclarations.add(declaration);
            }
        }
        List<Xir.Global> globalList = globals.entrySet().stream()
            .map(entry -> new Xir.Global(entry.getKey(), entry.getValue().type())).toList();
        List<Xir.Function> functions = new ArrayList<>();
        if (!globalDeclarations.isEmpty()) functions.add(lowerModuleInitializer(globalDeclarations));
        for (Ast.Item item : program.items()) {
            if (item instanceof Ast.FnDecl function) functions.add(new FunctionLowerer(function).lower());
        }
        return new Xir.Module(globalList, functions);
    }

    private Xir.Function lowerModuleInitializer(List<Ast.LetDecl> declarations) {
        FunctionLowerer lowerer = new FunctionLowerer("$module_init", Type.VOID, List.of());
        for (Ast.LetDecl declaration : declarations) {
            Xir.Value initializer = lowerer.expression(declaration.initializer());
            lowerer.emit(new Xir.Copy(globals.get(declaration.name()), initializer));
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
            this.name = declaration.name();
            this.returnType = namedType(declaration.returnType());
            this.scope = new Scope(null);
            this.current = block("entry");
            for (Ast.Param parameter : declaration.params()) {
                Xir.Value value = local(parameter.name(), namedType(parameter.type()));
                parameters.add(value);
                scope.define(parameter.name(), value);
            }
        }

        FunctionLowerer(String name, Type returnType, List<Xir.Value> parameters) {
            this.declaration = null;
            this.name = name;
            this.returnType = returnType;
            this.parameters.addAll(parameters);
            this.scope = new Scope(null);
            this.current = block("entry");
        }

        Xir.Function lower() {
            lowerBlock(declaration.body(), false);
            if (!terminated()) terminate(returnType == Type.VOID ? new Xir.Return(null) : new Xir.Unreachable());
            return finish();
        }

        Xir.Function finish() {
            List<Xir.BasicBlock> frozen = blocks.stream().map(MutableBlock::freeze).toList();
            return new Xir.Function(name, parameters, returnType, frozen);
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
                Xir.Value initializer = expression(let.initializer());
                Xir.Value variable = local(let.name(), types.typeOf(let.initializer()));
                scope.define(let.name(), variable);
                emit(new Xir.Copy(variable, initializer));
            } else if (statement instanceof Ast.ReturnStmt returned) {
                terminate(new Xir.Return(returned.value() == null ? null : expression(returned.value())));
            } else if (statement instanceof Ast.IfStmt conditional) {
                lowerIf(conditional);
            } else if (statement instanceof Ast.WhileStmt loop) {
                lowerWhile(loop);
            } else if (statement instanceof Ast.BreakStmt) {
                terminate(new Xir.Jump(loops.peek().breakLabel()));
            } else if (statement instanceof Ast.ContinueStmt) {
                terminate(new Xir.Jump(loops.peek().continueLabel()));
            } else if (statement instanceof Ast.Block block) {
                lowerBlock(block, true);
            } else if (statement instanceof Ast.ExprStmt expression) {
                expression(expression.expression());
            }
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
                Xir.Value result = temporary(types.typeOf(expression));
                emit(new Xir.Const(result, literal.value()));
                return result;
            }
            if (expression instanceof Ast.NameExpr nameExpression) {
                Xir.Value value = scope.lookup(nameExpression.name());
                return value == null ? globals.get(nameExpression.name()) : value;
            }
            if (expression instanceof Ast.GroupExpr group) return expression(group.expression());
            if (expression instanceof Ast.UnaryExpr unary) {
                Xir.Value operand = expression(unary.operand());
                Xir.Value result = temporary(types.typeOf(expression));
                emit(new Xir.Unary(result, unary.operator(), operand));
                return result;
            }
            if (expression instanceof Ast.BinaryExpr binary) {
                if (binary.operator() == TokenType.AMP_AMP || binary.operator() == TokenType.PIPE_PIPE) {
                    return shortCircuit(binary);
                }
                Xir.Value left = expression(binary.left());
                Xir.Value right = expression(binary.right());
                Xir.Value result = temporary(types.typeOf(expression));
                emit(new Xir.Binary(result, binary.operator(), left, right));
                return result;
            }
            if (expression instanceof Ast.AssignExpr assignment) return assignment(assignment);
            if (expression instanceof Ast.CallExpr call) {
                String function = ((Ast.NameExpr) call.callee()).name();
                List<Xir.Value> arguments = call.arguments().stream().map(this::expression).toList();
                Type type = types.typeOf(expression);
                Xir.Value result = type == Type.VOID ? null : temporary(type);
                emit(new Xir.Call(result, function, arguments));
                return result;
            }
            throw new IllegalStateException("P2 accepted an expression P3 cannot lower: " + expression.getClass());
        }

        private Xir.Value assignment(Ast.AssignExpr assignment) {
            String name = ((Ast.NameExpr) assignment.target()).name();
            Xir.Value target = scope.lookup(name);
            if (target == null) target = globals.get(name);
            Xir.Value value = expression(assignment.value());
            if (assignment.operator() != TokenType.ASSIGN) {
                Xir.Value combined = temporary(Type.INT);
                emit(new Xir.Binary(combined, compoundOperator(assignment.operator()), target, value));
                value = combined;
            }
            emit(new Xir.Copy(target, value));
            return target;
        }

        private Xir.Value shortCircuit(Ast.BinaryExpr binary) {
            Xir.Value result = temporary(Type.BOOL);
            Xir.Value left = expression(binary.left());
            MutableBlock rhsBlock = block(label("logic.rhs"));
            MutableBlock constantBlock = block(label("logic.short"));
            MutableBlock endBlock = block(label("logic.end"));
            if (binary.operator() == TokenType.AMP_AMP) {
                terminate(new Xir.Branch(left, rhsBlock.label, constantBlock.label));
            } else {
                terminate(new Xir.Branch(left, constantBlock.label, rhsBlock.label));
            }
            current = constantBlock;
            Xir.Value constant = temporary(Type.BOOL);
            emit(new Xir.Const(constant, binary.operator() == TokenType.PIPE_PIPE));
            emit(new Xir.Copy(result, constant));
            terminate(new Xir.Jump(endBlock.label));

            current = rhsBlock;
            Xir.Value right = expression(binary.right());
            emit(new Xir.Copy(result, right));
            terminate(new Xir.Jump(endBlock.label));
            current = endBlock;
            return result;
        }

        private Xir.Value temporary(Type type) {
            return new Xir.Value("%t" + temporaryCounter++, type);
        }

        private Xir.Value local(String sourceName, Type type) {
            return new Xir.Value("%" + sourceName + "." + localCounter++, type);
        }

        private MutableBlock block(String label) {
            MutableBlock block = new MutableBlock(label);
            blocks.add(block);
            return block;
        }

        private String label(String prefix) {
            return prefix + "." + labelCounter++;
        }

        private void emit(Xir.Instruction instruction) {
            if (terminated()) throw new IllegalStateException("instruction emitted after terminator");
            current.instructions.add(instruction);
        }

        private void terminate(Xir.Terminator terminator) {
            if (terminated()) throw new IllegalStateException("basic block already terminated");
            current.terminator = terminator;
        }

        private void jumpIfOpen(String target) {
            if (!terminated()) terminate(new Xir.Jump(target));
        }

        private boolean terminated() {
            return current.terminator != null;
        }
    }

    private static Type namedType(Ast.TypeRef reference) {
        if (reference == null) return Type.VOID;
        Ast.NamedType named = (Ast.NamedType) reference;
        return switch (named.name()) {
            case "int" -> Type.INT;
            case "bool" -> Type.BOOL;
            case "void" -> Type.VOID;
            default -> throw new IllegalStateException("unsupported checked type " + named.name());
        };
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
        private final Map<String, Xir.Value> values = new LinkedHashMap<>();
        Scope(Scope parent) { this.parent = parent; }
        void define(String name, Xir.Value value) { values.put(name, value); }
        Xir.Value lookup(String name) {
            Xir.Value value = values.get(name);
            return value != null || parent == null ? value : parent.lookup(name);
        }
    }

    private record LoopTargets(String breakLabel, String continueLabel) {}
}
