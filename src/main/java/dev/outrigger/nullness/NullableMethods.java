package dev.outrigger.nullness;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Decides which methods can return null without being annotated
 * {@code @Nullable}: a {@code return} yields {@code null}, a ternary with a
 * {@code null} branch, the result of another such method, or a local variable
 * that can hold one of those and isn't null-checked before the {@code return}.
 *
 * <p>Calls are resolved with {@link MethodResolver}, so methods of nested
 * classes and of the module's other files are covered.
 */
final class NullableMethods {

    /** How many calls deep to follow, so that long call chains stay cheap. */
    private static final int MAX_DEPTH = 4;

    private final MethodResolver resolver;
    private final Map<MethodDeclaration, Boolean> nullable = new IdentityHashMap<>();
    private int depth;

    NullableMethods(MethodResolver resolver) {
        this.resolver = resolver;
    }

    /** Whether {@code method} can return null although it isn't annotated to. */
    boolean isNullable(MethodDeclaration method) {
        Boolean known = nullable.get(method);
        if (known != null) {
            return known;
        }
        if (method.getBody().isEmpty() || isAnnotated(method) || depth >= MAX_DEPTH) {
            return false;
        }
        nullable.put(method, false); // a recursive call doesn't make it nullable
        depth++;
        try {
            boolean result = returns(method).anyMatch(ret -> returnsNull(ret, method));
            nullable.put(method, result);
            return result;
        } finally {
            depth--;
        }
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
        if (NullChecks.unwrap(expression) instanceof MethodCallExpr call) {
            return resolver.target(call).filter(this::isNullable);
        }
        return Optional.empty();
    }

    private boolean returnsNull(ReturnStmt ret, MethodDeclaration method) {
        Optional<Expression> value = ret.getExpression().map(NullChecks::unwrap);
        if (value.isEmpty()) {
            return false;
        }
        if (mayBeNull(value.get())) {
            return true;
        }
        // return of a local variable that can hold null and isn't checked before
        return value.get() instanceof NameExpr name
                && holdsNull(method, name.getNameAsString())
                && !NullChecks.isChecked(value.get(), name.getNameAsString(), false);
    }

    /** Whether a local variable of {@code method} is declared or assigned with a value that can be null. */
    private boolean holdsNull(MethodDeclaration method, String var) {
        Stream<Expression> declared = method.findAll(VariableDeclarator.class,
                        declarator -> declarator.getNameAsString().equals(var))
                .stream()
                .flatMap(declarator -> declarator.getInitializer().stream());
        Stream<Expression> assigned = method.findAll(AssignExpr.class,
                        assign -> NullChecks.isVar(assign.getTarget(), var))
                .stream()
                .map(AssignExpr::getValue);
        return Stream.concat(declared, assigned).anyMatch(this::mayBeNull);
    }

    private static Stream<ReturnStmt> returns(MethodDeclaration method) {
        return method.findAll(ReturnStmt.class, ret -> ownedBy(ret, method)).stream();
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

    /**
     * Annotated methods are left to jdtls: {@code @Nullable} ones it checks
     * itself, {@code @NotNull}/{@code @Nonnull} ones promise not to return null.
     */
    private static boolean isAnnotated(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString())
                .anyMatch(name -> name.endsWith("Nullable") || name.endsWith("NotNull")
                        || name.endsWith("Nonnull") || name.endsWith("NonNull"));
    }
}
