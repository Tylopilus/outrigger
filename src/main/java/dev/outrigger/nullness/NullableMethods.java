package dev.outrigger.nullness;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the methods of a compilation unit that can return null without being
 * annotated {@code @Nullable}: a {@code return} yields {@code null}, a ternary
 * with a {@code null} branch, or the result of another such method.
 *
 * <p>Methods are matched by name and argument count, and only calls on
 * {@code this} (explicit or implicit) are resolved.
 */
final class NullableMethods {

    private final Map<String, MethodDeclaration> nullable = new HashMap<>();

    NullableMethods(Node root) {
        List<MethodDeclaration> candidates = root.findAll(MethodDeclaration.class,
                method -> method.getBody().isPresent() && !isAnnotatedNullable(method));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (MethodDeclaration method : candidates) {
                String key = key(method.getNameAsString(), method.getParameters().size());
                if (!nullable.containsKey(key) && returnsNull(method)) {
                    nullable.put(key, method);
                    changed = true;
                }
            }
        }
    }

    boolean contains(MethodDeclaration method) {
        return nullable.containsValue(method);
    }

    /** Whether evaluating {@code expression} can produce null. */
    boolean mayBeNull(Expression expression) {
        Expression expr = NullChecks.unwrap(expression);
        if (expr instanceof NullLiteralExpr) {
            return true;
        }
        if (expr instanceof ConditionalExpr ternary) {
            return mayBeNull(ternary.getThenExpr()) || mayBeNull(ternary.getElseExpr());
        }
        return resolve(expr).isPresent();
    }

    /** The nullable method that {@code expression} calls, if it is such a call. */
    Optional<MethodDeclaration> resolve(Expression expression) {
        if (NullChecks.unwrap(expression) instanceof MethodCallExpr call
                && call.getScope().map(scope -> scope instanceof ThisExpr).orElse(true)) {
            return Optional.ofNullable(nullable.get(key(call.getNameAsString(), call.getArguments().size())));
        }
        return Optional.empty();
    }

    private boolean returnsNull(MethodDeclaration method) {
        return method.findAll(ReturnStmt.class, ret -> ownedBy(ret, method)).stream()
                .anyMatch(ret -> ret.getExpression().map(this::mayBeNull).orElse(false));
    }

    /** Whether {@code node} belongs to {@code method} itself, not to a nested lambda or class. */
    private static boolean ownedBy(Node node, MethodDeclaration method) {
        Node current = node.getParentNode().orElse(null);
        while (current != null && current != method) {
            if (current instanceof LambdaExpr || current instanceof TypeDeclaration<?>
                    || current instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
                return false;
            }
            current = current.getParentNode().orElse(null);
        }
        return current == method;
    }

    private static boolean isAnnotatedNullable(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> a.getNameAsString().endsWith("Nullable"));
    }

    private static String key(String name, int arity) {
        return name + "/" + arity;
    }
}
