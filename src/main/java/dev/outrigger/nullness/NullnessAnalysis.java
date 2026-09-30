package dev.outrigger.nullness;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private NullnessAnalysis(TextDocument document, PositionEncoding encoding, CompilationUnit unit) {
        this.document = document;
        this.encoding = encoding;
        this.unit = unit;
        this.nullable = new NullableMethods(unit);
        findUncheckedUses();
    }

    /** Parses the document; empty when it does not parse (e.g. while typing). */
    static Optional<NullnessAnalysis> of(TextDocument document, PositionEncoding encoding) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setTabSize(1);
        ParseResult<CompilationUnit> result = new JavaParser(config).parse(document.text());
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new NullnessAnalysis(document, encoding, result.getResult().get()));
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
                if (initializer.isEmpty() || !nullable.mayBeNull(initializer.get())
                        || enclosingCallable(declarator) != callable) {
                    continue;
                }
                String var = declarator.getNameAsString();
                if (callable.findFirst(AssignExpr.class, a -> NullChecks.isVar(a.getTarget(), var)).isPresent()) {
                    continue; // re-assigned: its nullness depends on flow we do not follow
                }
                tracked.computeIfAbsent(callable, c -> new HashSet<>()).add(var);
                String reason = nullable.resolve(initializer.get())
                        .map(m -> " (" + m.getNameAsString() + "() can return null)")
                        .orElse("");
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
                    nullable.resolve(scope).ifPresent(method -> report(scope,
                            "Potential null pointer access: " + method.getNameAsString() + "() can return null"));
                }
            }
        }
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
        return ret.flatMap(r -> r.findAncestor(MethodDeclaration.class)).map(nullable::contains).orElse(false);
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
