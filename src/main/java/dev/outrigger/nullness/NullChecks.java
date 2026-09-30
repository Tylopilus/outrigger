package dev.outrigger.nullness;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a local variable is known not to be null at a point in the
 * code, from the null checks that guard it.
 *
 * <p>Understands literal {@code x == null} tests as well as library checks that
 * Eclipse's analysis does not: {@code ObjectUtils.anyNull/allNotNull},
 * {@code Objects.isNull/nonNull/requireNonNull} and
 * {@code StringUtils.isBlank/isEmpty/isNotBlank/isNotEmpty}.
 */
final class NullChecks {

    /** Method name → the value it returns when its argument is not null. */
    private static final Map<String, Boolean> NON_NULL_WHEN = Map.of(
            "allNotNull", true,
            "nonNull", true,
            "isNotBlank", true,
            "isNotEmpty", true,
            "anyNull", false,
            "isNull", false,
            "isBlank", false,
            "isEmpty", false);

    private NullChecks() {
    }

    static Expression unwrap(Expression expression) {
        while (expression instanceof EnclosedExpr enclosed) {
            expression = enclosed.getInner();
        }
        return expression;
    }

    static boolean isVar(Expression expression, String var) {
        return unwrap(expression) instanceof NameExpr name && name.getNameAsString().equals(var);
    }

    /** Whether {@code condition} evaluating to {@code truth} proves that {@code var} is not null. */
    static boolean proves(Expression condition, String var, boolean truth) {
        Expression cond = unwrap(condition);
        if (cond instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
            return proves(unary.getExpression(), var, !truth);
        }
        if (cond instanceof BinaryExpr binary) {
            Expression left = binary.getLeft();
            Expression right = binary.getRight();
            switch (binary.getOperator()) {
                case OR, AND -> {
                    boolean eachHasSameValue = (binary.getOperator() == BinaryExpr.Operator.OR) != truth;
                    return eachHasSameValue
                            ? proves(left, var, truth) || proves(right, var, truth)
                            : proves(left, var, truth) && proves(right, var, truth);
                }
                case EQUALS, NOT_EQUALS -> {
                    boolean compared = (unwrap(left) instanceof NullLiteralExpr && isVar(right, var))
                            || (unwrap(right) instanceof NullLiteralExpr && isVar(left, var));
                    return compared && (binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS) == truth;
                }
                default -> {
                    return false;
                }
            }
        }
        if (cond instanceof MethodCallExpr call) {
            Boolean nonNullWhen = NON_NULL_WHEN.get(call.getNameAsString());
            return nonNullWhen != null && nonNullWhen == truth
                    && call.getArguments().stream().anyMatch(arg -> isVar(arg, var));
        }
        return false;
    }

    /**
     * Whether {@code var} is known not to be null at {@code node}.
     *
     * @param crossLambda continue into the enclosing method for captured
     *                    variables; only sound for variables never re-assigned
     */
    static boolean isChecked(Node node, String var, boolean crossLambda) {
        Node child = node;
        Node parent = node.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof MethodDeclaration || parent instanceof ConstructorDeclaration) {
                return false;
            }
            if (parent instanceof LambdaExpr && !crossLambda) {
                return false;
            }
            if (parent instanceof BinaryExpr binary && binary.getRight() == child) {
                if (binary.getOperator() == BinaryExpr.Operator.OR && proves(binary.getLeft(), var, false)
                        || binary.getOperator() == BinaryExpr.Operator.AND && proves(binary.getLeft(), var, true)) {
                    return true;
                }
            } else if (parent instanceof IfStmt ifStmt) {
                if (ifStmt.getThenStmt() == child && proves(ifStmt.getCondition(), var, true)
                        || ifStmt.getElseStmt().orElse(null) == child && proves(ifStmt.getCondition(), var, false)) {
                    return true;
                }
            } else if (parent instanceof ConditionalExpr ternary) {
                if (ternary.getThenExpr() == child && proves(ternary.getCondition(), var, true)
                        || ternary.getElseExpr() == child && proves(ternary.getCondition(), var, false)) {
                    return true;
                }
            } else if (parent instanceof WhileStmt loop) {
                if (loop.getBody() == child && proves(loop.getCondition(), var, true)) {
                    return true;
                }
            } else if (parent instanceof BlockStmt block && child instanceof Statement statement) {
                Boolean earlier = checkedByEarlierStatements(block.getStatements(), statement, var);
                if (earlier != null) {
                    return earlier;
                }
            }
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * Looks at the statements before {@code statement} in its block: an
     * {@code if (<null check>) return;} or {@code requireNonNull} proves it,
     * an assignment to the variable ends the search. {@code null} means
     * undecided, keep looking further out.
     */
    private static Boolean checkedByEarlierStatements(List<Statement> statements, Statement statement, String var) {
        for (int i = statements.indexOf(statement) - 1; i >= 0; i--) {
            Statement previous = statements.get(i);
            if (requiresNonNull(previous, var)) {
                return true;
            }
            if (assigns(previous, var)) {
                return false;
            }
            if (previous instanceof IfStmt ifStmt && ifStmt.getElseStmt().isEmpty()
                    && alwaysExits(ifStmt.getThenStmt()) && proves(ifStmt.getCondition(), var, false)) {
                return true;
            }
        }
        return null;
    }

    private static boolean requiresNonNull(Statement statement, String var) {
        return statement instanceof ExpressionStmt expression
                && expression.getExpression() instanceof MethodCallExpr call
                && call.getNameAsString().equals("requireNonNull")
                && call.getArguments().isNonEmpty()
                && isVar(call.getArgument(0), var);
    }

    static boolean alwaysExits(Statement statement) {
        if (statement instanceof BlockStmt block) {
            return block.getStatements().isNonEmpty() && alwaysExits(block.getStatements().getLast().orElseThrow());
        }
        return statement instanceof ReturnStmt || statement instanceof ThrowStmt
                || statement instanceof BreakStmt || statement instanceof ContinueStmt;
    }

    /** Whether {@code node} assigns or declares {@code var}. */
    static boolean assigns(Node node, String var) {
        return node.findFirst(AssignExpr.class, assign -> isVar(assign.getTarget(), var)).isPresent()
                || node.findFirst(VariableDeclarator.class, declarator -> declarator.getNameAsString().equals(var))
                        .isPresent();
    }
}
