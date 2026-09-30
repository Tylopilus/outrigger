package dev.outrigger.nullness;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import dev.outrigger.library.LibraryNullness;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Null analysis applied to the diagnostics of one document:
 * <ul>
 * <li>drops "may be null" warnings that a library null check rules out,</li>
 * <li>reports uses of results of unannotated nullable methods without a null check,</li>
 * <li>drops "Dead code" on null checks of such results, which Eclipse reports
 * because it assumes the result is not null,</li>
 * <li>drops "Null type mismatch" on the {@code return} statements of such methods.</li>
 * </ul>
 */
final class NullnessAnalysis {

    static final String POTENTIAL_NULL = "536871364"; // IProblem.PotentialNullLocalVariableReference
    static final String DEAD_CODE = "536871061"; // IProblem.DeadCode
    static final Set<String> NULL_TYPE_MISMATCH = Set.of("969", "970");
    static final String SOURCE = "outrigger";
    static final String CODE_NULLABLE_RESULT = "nullable-result";

    private static final int SEVERITY_WARNING = 2;

    private final TextDocument document;
    private final PositionEncoding encoding;
    private final CompilationUnit unit;
    private final NullableMethods nullable;
    /** Tracked variables (holding nullable results) per method or constructor. */
    private final Map<CallableDeclaration<?>, Set<String>> tracked = new HashMap<>();
    private final JsonArray added = new JsonArray();

    private NullnessAnalysis(TextDocument document, PositionEncoding encoding, CompilationUnit unit,
            TypeSolver typeSolver, LibraryNullness library, boolean reportLibrary) {
        this.document = document;
        this.encoding = encoding;
        this.unit = unit;
        this.nullable = new NullableMethods(new MethodResolver(typeSolver), library, reportLibrary);
        findUncheckedUses();
        findUncheckedFields();
    }

    /**
     * Parses the document; empty when it does not parse (e.g. while typing).
     * {@code projects} gives the project of a source folder once its classpath
     * is known: its other modules' sources, jars and library nullness.
     */
    static Optional<NullnessAnalysis> of(TextDocument document, PositionEncoding encoding,
            Function<Path, Optional<Projects.Project>> projects) {
        ParseResult<CompilationUnit> result = new JavaParser(parserConfiguration()).parse(document.text());
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return Optional.empty();
        }
        CompilationUnit unit = result.getResult().get();
        Optional<Path> root = sourceRoot(document, unit);
        Optional<Projects.Project> project = root.flatMap(projects);

        // This module's sources, the other modules' sources, the jars, the JDK
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        root.ifPresent(dir -> addSources(typeSolver, dir));
        project.ifPresent(p -> p.sourceRoots().stream()
                .filter(dir -> !root.get().equals(dir))
                .forEach(dir -> addSources(typeSolver, dir)));
        typeSolver.add(project.<TypeSolver>map(p -> new SharedTypeSolver(p.jars())).orElseGet(ReflectionTypeSolver::new));
        new JavaSymbolSolver(typeSolver).inject(unit);

        boolean inTests = root.map(dir -> dir.toString().contains("/src/test/")).orElse(false);
        boolean reportLibrary = !inTests || project.map(Projects.Project::libraryWarningsInTests).orElse(false);
        return Optional.of(new NullnessAnalysis(document, encoding, unit, typeSolver,
                project.map(Projects.Project::nullness).orElse(null), reportLibrary));
    }

    /** Test and tool entry point without a project: this file's module and the JDK. */
    static Optional<NullnessAnalysis> of(TextDocument document, PositionEncoding encoding) {
        return of(document, encoding, root -> Optional.empty());
    }

    private static void addSources(CombinedTypeSolver typeSolver, Path dir) {
        try {
            typeSolver.add(new JavaParserTypeSolver(dir, parserConfiguration()));
        } catch (RuntimeException e) {
            // e.g. an unreadable directory: go on without it
        }
    }

    private static ParserConfiguration parserConfiguration() {
        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setTabSize(1);
    }

    /**
     * The source folder the file belongs to: its directory without the
     * directories of its package, e.g. {@code .../src/main/java} for
     * {@code .../src/main/java/com/example/Foo.java} in {@code com.example}.
     * Empty without a package, as the folder is then unknown (and would be
     * scanned completely).
     */
    static Optional<Path> sourceRoot(TextDocument document, CompilationUnit unit) {
        try {
            URI uri = URI.create(document.uri());
            if (!"file".equals(uri.getScheme())) {
                return Optional.empty();
            }
            if (unit.getPackageDeclaration().isEmpty()) {
                return Optional.empty();
            }
            Path dir = Path.of(uri).getParent();
            List<String> packagePath = List.of(unit.getPackageDeclaration().get().getNameAsString().split("\\."));
            for (int i = packagePath.size() - 1; i >= 0; i--) {
                if (dir == null || !dir.getFileName().toString().equals(packagePath.get(i))) {
                    return Optional.empty();
                }
                dir = dir.getParent();
            }
            return dir != null && Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    JsonArray apply(JsonArray diagnostics) {
        JsonArray result = new JsonArray();
        for (JsonElement element : diagnostics) {
            if (!(element.isJsonObject() && isRuledOut(element.getAsJsonObject()))) {
                result.add(element);
            }
        }
        result.addAll(added);
        return result;
    }

    private boolean isRuledOut(JsonObject diagnostic) {
        String code = diagnostic.has("code") ? diagnostic.get("code").getAsString() : "";
        if (code.equals(POTENTIAL_NULL)) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("The variable (\\S+) may be null")
                    .matcher(diagnostic.get("message").getAsString());
            return m.find() && nodeAt(diagnostic).map(node -> NullChecks.isChecked(node, m.group(1), false)).orElse(false);
        }
        if (code.equals(DEAD_CODE)) {
            return nodeAt(diagnostic).map(this::isNullCheckOfTrackedVariable).orElse(false);
        }
        if (NULL_TYPE_MISMATCH.contains(code)) {
            return nodeAt(diagnostic).map(this::isInNullableReturn).orElse(false);
        }
        return false;
    }

    private void findUncheckedUses() {
        for (CallableDeclaration<?> callable : unit.findAll(CallableDeclaration.class)) {
            for (VariableDeclarator declarator : callable.findAll(VariableDeclarator.class)) {
                Optional<Expression> initializer = declarator.getInitializer();
                Optional<String> why = initializer.flatMap(nullable::reportable);
                if (why.isEmpty() || enclosingCallable(declarator) != callable) {
                    continue; // not nullable, or annotated @Nullable: then jdtls reports it
                }
                String var = declarator.getNameAsString();
                if (callable.findFirst(AssignExpr.class, a -> NullChecks.isVar(a.getTarget(), var)).isPresent()) {
                    continue; // re-assigned: its nullness depends on flow we do not follow
                }
                tracked.computeIfAbsent(callable, c -> new HashSet<>()).add(var);
                String reason = why.get().isEmpty() ? "" : " (" + why.get() + ")";
                Position declared = declarator.getBegin().orElseThrow();
                for (Expression scope : dereferencedScopes(callable)) {
                    if (NullChecks.isVar(scope, var) && scope.getBegin().orElseThrow().isAfter(declared)
                            && !NullChecks.isChecked(scope, var, true)) {
                        report(scope, "Potential null pointer access: " + var + " may be null" + reason);
                    }
                }
            }
            for (Expression scope : dereferencedScopes(callable)) {
                if (enclosingCallable(scope) == callable) {
                    nullable.reportable(scope).filter(why -> !why.isEmpty())
                            .filter(why -> !NullChecks.isCheckedExpression(scope)) // map.get(k) != null && map.get(k)...
                            .ifPresent(why -> report(scope, "Potential null pointer access: " + why));
                }
            }
        }
    }

    /**
     * Fields initialized with a value that can be null and never assigned
     * again: their uses in the methods of the class are checked like local
     * variables.
     */
    private void findUncheckedFields() {
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            TypeDeclaration<?> owner = field.findAncestor(TypeDeclaration.class).orElse(null);
            for (VariableDeclarator declarator : field.getVariables()) {
                Optional<String> why = declarator.getInitializer().flatMap(nullable::reportable);
                String name = declarator.getNameAsString();
                if (owner == null || why.isEmpty()
                        || unit.findFirst(AssignExpr.class, a -> NullChecks.isVar(a.getTarget(), name)).isPresent()) {
                    continue;
                }
                String reason = why.get().isEmpty() ? "" : " (" + why.get() + ")";
                for (CallableDeclaration<?> callable : owner.findAll(CallableDeclaration.class)) {
                    if (declaresLocally(callable, name)) {
                        continue; // a local variable or parameter hides the field
                    }
                    tracked.computeIfAbsent(callable, c -> new HashSet<>()).add(name);
                    for (Expression scope : dereferencedScopes(callable)) {
                        if (enclosingCallable(scope) == callable && NullChecks.isVar(scope, name)
                                && !NullChecks.isChecked(scope, name, true)) {
                            report(scope, "Potential null pointer access: field " + name + " may be null" + reason);
                        }
                    }
                }
            }
        }
    }

    private static boolean declaresLocally(CallableDeclaration<?> callable, String name) {
        return callable.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))
                || callable.findFirst(VariableDeclarator.class, d -> d.getNameAsString().equals(name)).isPresent();
    }

    /** Expressions that are dereferenced: the scope of a method call or field access. */
    private static List<Expression> dereferencedScopes(Node root) {
        List<Expression> scopes = new ArrayList<>();
        root.findAll(MethodCallExpr.class).forEach(call -> call.getScope().ifPresent(scopes::add));
        root.findAll(FieldAccessExpr.class).forEach(access -> scopes.add(access.getScope()));
        return scopes;
    }

    private boolean isNullCheckOfTrackedVariable(Node node) {
        Set<String> vars = tracked.getOrDefault(enclosingCallable(node), Set.of());
        for (Node current = node; current != null; current = current.getParentNode().orElse(null)) {
            if (current instanceof IfStmt ifStmt && !contains(ifStmt.getCondition(), node)) {
                boolean comparesTracked = ifStmt.getCondition().findFirst(BinaryExpr.class,
                        b -> vars.stream().anyMatch(v -> NullChecks.proves(b, v, true) || NullChecks.proves(b, v, false)))
                        .isPresent();
                if (comparesTracked) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInNullableReturn(Node node) {
        Optional<ReturnStmt> ret = node instanceof ReturnStmt r ? Optional.of(r) : node.findAncestor(ReturnStmt.class);
        return ret.flatMap(r -> r.findAncestor(MethodDeclaration.class)).map(nullable::isNullable).orElse(false);
    }

    private static CallableDeclaration<?> enclosingCallable(Node node) {
        return node.findAncestor(CallableDeclaration.class).orElse(null);
    }

    private static boolean contains(Node outer, Node inner) {
        for (Node current = inner; current != null; current = current.getParentNode().orElse(null)) {
            if (current == outer) {
                return true;
            }
        }
        return false;
    }

    /** The innermost node at the start of a diagnostic's range. */
    private Optional<Node> nodeAt(JsonObject diagnostic) {
        JsonObject start = diagnostic.getAsJsonObject("range").getAsJsonObject("start");
        int line = start.get("line").getAsInt();
        int column = encoding.toCharIndex(document.line(line), start.get("character").getAsInt()) + 1;
        Position position = new Position(line + 1, column);
        Node node = unit;
        boolean descended = true;
        while (descended) {
            descended = false;
            for (Node child : node.getChildNodes()) {
                if (child.getRange().map(r -> r.contains(position)).orElse(false)) {
                    node = child;
                    descended = true;
                    break;
                }
            }
        }
        return node == unit ? Optional.empty() : Optional.of(node);
    }

    private void report(Node node, String message) {
        Range range = node.getRange().orElseThrow();
        JsonObject diagnostic = new JsonObject();
        diagnostic.add("range", lspRange(range));
        diagnostic.addProperty("severity", SEVERITY_WARNING);
        diagnostic.addProperty("source", SOURCE);
        diagnostic.addProperty("code", CODE_NULLABLE_RESULT);
        diagnostic.addProperty("message", message);
        added.add(diagnostic);
    }

    /** JavaParser ranges are 1-based and end-inclusive; LSP ranges are 0-based and end-exclusive. */
    private JsonObject lspRange(Range range) {
        JsonObject lsp = new JsonObject();
        lsp.add("start", lspPosition(range.begin.line - 1, range.begin.column - 1));
        lsp.add("end", lspPosition(range.end.line - 1, range.end.column));
        return lsp;
    }

    private JsonObject lspPosition(int line, int charIndex) {
        JsonObject position = new JsonObject();
        position.addProperty("line", line);
        position.addProperty("character", encoding.fromCharIndex(document.line(line), charIndex));
        return position;
    }
}
