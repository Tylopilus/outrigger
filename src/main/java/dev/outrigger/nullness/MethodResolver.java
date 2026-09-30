package dev.outrigger.nullness;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the declaration a method call invokes, when its source is available:
 * in the same file or, through the type solver, in the module's other files.
 *
 * <p>Uses the symbol solver's exact resolution first. That fails when e.g. an
 * argument's type comes from a library jar the solver doesn't know, so it
 * then looks the method up by name and argument count in the type the call is
 * made on: the enclosing classes for unqualified calls, or the declared type
 * of the variable or class the call is qualified with.
 */
final class MethodResolver {

    private final TypeSolver typeSolver;
    private final JavaParserFacade facade;
    private final Map<MethodCallExpr, Optional<Target>> cache = new IdentityHashMap<>();

    /** What a call invokes: a method with source in the project, or a library method. */
    sealed interface Target {
        record Source(MethodDeclaration declaration) implements Target {
        }

        /** {@code owner} is an internal name ({@code com/day/cq/wcm/api/PageManager}). */
        record Library(String owner, String name, String descriptor) implements Target {
            String display() {
                return owner.substring(Math.max(owner.lastIndexOf('/'), owner.lastIndexOf('$')) + 1) + "." + name
                        + "()";
            }
        }
    }

    MethodResolver(TypeSolver typeSolver) {
        this.typeSolver = typeSolver;
        this.facade = JavaParserFacade.get(typeSolver);
    }

    Optional<Target> target(MethodCallExpr call) {
        return cache.computeIfAbsent(call, this::find);
    }

    private Optional<Target> find(MethodCallExpr call) {
        try {
            SymbolReference<ResolvedMethodDeclaration> solved = facade.solve(call);
            if (solved.isSolved()) {
                ResolvedMethodDeclaration method = solved.getCorrespondingDeclaration();
                Optional<MethodDeclaration> source = method.toAst(MethodDeclaration.class);
                return source.isPresent() ? source.map(Target.Source::new) : library(method);
            }
        } catch (RuntimeException | StackOverflowError e) {
            // unresolved argument types and the like: fall back to the lookup by name
        }
        return byName(call).map(Target.Source::new);
    }

    private static Optional<Target> library(ResolvedMethodDeclaration method) {
        try {
            String packageName = method.declaringType().getPackageName();
            String className = method.declaringType().getClassName().replace('.', '$');
            String owner = (packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/") + className;
            return Optional.of(new Target.Library(owner, method.getName(), method.toDescriptor()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<MethodDeclaration> byName(MethodCallExpr call) {
        String name = call.getNameAsString();
        int arity = call.getArguments().size();
        Optional<Expression> scope = call.getScope();

        if (scope.isEmpty() || scope.get() instanceof ThisExpr thisExpr && thisExpr.getTypeName().isEmpty()) {
            // Like Java: the innermost enclosing class that has a method of that name
            for (Node node = call.getParentNode().orElse(null); node != null; node = node.getParentNode().orElse(null)) {
                List<MethodDeclaration> methods = methodsNamed(node, name);
                if (!methods.isEmpty()) {
                    return unique(methods, arity);
                }
            }
            return Optional.empty();
        }
        return typeOf(scope.get()).flatMap(type -> unique(type.getMethodsByName(name), arity));
    }

    /** Methods named {@code name} that {@code node} declares, if it is a class body. */
    private static List<MethodDeclaration> methodsNamed(Node node, String name) {
        if (node instanceof TypeDeclaration<?> type) {
            return type.getMethodsByName(name);
        }
        if (node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) {
            return creation.getAnonymousClassBody().get().stream()
                    .filter(member -> member instanceof MethodDeclaration method
                            && method.getNameAsString().equals(name))
                    .map(MethodDeclaration.class::cast)
                    .toList();
        }
        return List.of();
    }

    /** The only method with {@code arity} parameters: with overloads, nothing is assumed. */
    private static Optional<MethodDeclaration> unique(List<MethodDeclaration> methods, int arity) {
        List<MethodDeclaration> matching = methods.stream()
                .filter(method -> method.getParameters().size() == arity)
                .toList();
        return matching.size() == 1 ? Optional.of(matching.getFirst()) : Optional.empty();
    }

    /** The declaration of the type an expression evaluates to, or names (for static calls). */
    private Optional<TypeDeclaration<?>> typeOf(Expression scope) {
        try {
            ResolvedType type = facade.getType(scope);
            if (type.isReferenceType()) {
                return type.asReferenceType().getTypeDeclaration().flatMap(MethodResolver::sourceOf);
            }
        } catch (RuntimeException | StackOverflowError e) {
            // not a value: try it as a type name below
        }
        if (scope instanceof NameExpr name) {
            try {
                SymbolReference<ResolvedTypeDeclaration> type =
                        JavaParserFactory.getContext(scope, typeSolver).solveType(name.getNameAsString());
                if (type.isSolved()) {
                    return sourceOf(type.getCorrespondingDeclaration());
                }
            } catch (RuntimeException | StackOverflowError e) {
                // unknown type
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<TypeDeclaration<?>> sourceOf(ResolvedTypeDeclaration type) {
        return type.toAst(TypeDeclaration.class).map(declaration -> (TypeDeclaration<?>) declaration);
    }
}
