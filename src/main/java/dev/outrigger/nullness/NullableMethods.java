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
import dev.outrigger.library.LibraryNullness;
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

    /** How an expression can be null. */
    enum Kind {
        NONE,
        /** The result of a method annotated {@code @Nullable}: jdtls reports its unchecked uses. */
        ANNOTATED,
        /** Found out by Outrigger. */
        INFERRED
    }

    /** Whether an expression can be null, why (for the message; may be empty), and whether a library says so. */
    record Nullability(Kind kind, String reason, boolean library) {
        static final Nullability NONE = new Nullability(Kind.NONE, "", false);

        Nullability(Kind kind, String reason) {
            this(kind, reason, false);
        }
    }

    private final MethodResolver resolver;
    private final LibraryNullness library;
    private final boolean reportLibrary;
    private final Map<MethodDeclaration, Boolean> nullable = new IdentityHashMap<>();
    private int depth;

    /**
     * {@code library} decides for methods without source; null to not look at
     * them. {@code reportLibrary} says whether unchecked uses of nullable
     * library methods are reported (not in test sources by default).
     */
    NullableMethods(MethodResolver resolver, LibraryNullness library, boolean reportLibrary) {
        this.resolver = resolver;
        this.library = library;
        this.reportLibrary = reportLibrary;
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
        return of(expression).kind() != Kind.NONE;
    }

    /** Why {@code expression} can be null, when Outrigger found out (not jdtls through an annotation). */
    Optional<String> reportable(Expression expression) {
        Nullability nullability = of(expression);
        return nullability.kind() == Kind.INFERRED && (reportLibrary || !nullability.library())
                ? Optional.of(nullability.reason())
                : Optional.empty();
    }

    Nullability of(Expression expression) {
        Expression expr = NullChecks.unwrap(expression);
        if (expr instanceof NullLiteralExpr) {
            return new Nullability(Kind.INFERRED, "");
        }
        if (expr instanceof ConditionalExpr ternary) {
            Nullability then = of(ternary.getThenExpr());
            Nullability otherwise = of(ternary.getElseExpr());
            return then.kind().compareTo(otherwise.kind()) >= 0 ? then : otherwise;
        }
        if (!(expr instanceof MethodCallExpr call)) {
            return Nullability.NONE;
        }
        return resolver.target(call).map(target -> switch (target) {
            case MethodResolver.Target.Source source -> isNullable(source.declaration())
                    ? new Nullability(Kind.INFERRED, source.declaration().getNameAsString() + "() can return null")
                    : Nullability.NONE;
            case MethodResolver.Target.Library method -> library == null ? Nullability.NONE : fromLibrary(method);
        }).orElse(Nullability.NONE);
    }

    private Nullability fromLibrary(MethodResolver.Target.Library method) {
        LibraryNullness.Verdict verdict = library.of(method.owner(), method.name(), method.descriptor());
        return switch (verdict.kind()) {
            case NOT_NULLABLE -> Nullability.NONE;
            case ANNOTATED -> new Nullability(Kind.ANNOTATED, method.display() + " is @Nullable", true);
            case NULLABLE -> new Nullability(Kind.INFERRED,
                    method.display() + " can return null: " + verdict.reason(), true);
        };
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
