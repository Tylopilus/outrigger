package dev.outrigger.library;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Reads a library method's contract from its Javadoc: whether the documented
 * return value can be null ("the page or {@code null}", "null if not found").
 * Methods without Javadoc, or with {@code {@inheritDoc}}, take it from the
 * method they override.
 */
final class JavadocNullness {

    /** Inline tags keep their text ({@code {@code null}} → {@code null}), HTML tags go. */
    private static final Pattern MARKUP = Pattern.compile("\\{@\\w+\\s*([^}]*)}|<[^>]+>");
    private static final Pattern NULLABLE = Pattern.compile(
            "\\b(?:or|may be|might be|can be|could be|possibly|returns?|otherwise|else)\\s+null\\b"
                    + "|\\bnull\\s+(?:if|when|in case|is returned|otherwise|for|on)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED = Pattern.compile(
            "(?:never|not|cannot|can't|won't|will not|must not|should not|doesn't|does not)\\s+(?:\\w+\\s+)?$",
            Pattern.CASE_INSENSITIVE);
    /** Null only for null input: "{@code null} if null String input", "null if the key is null". */
    private static final Pattern FOR_NULL_INPUT = Pattern.compile(
            "\\bif\\s+(?:\\w+\\s+){0,4}?(?:is\\s+|are\\s+)?null\\b|\\bnull\\s+(?:\\w+\\s+){0,2}input\\b",
            Pattern.CASE_INSENSITIVE);
    /** "A null input String returns null": the input before the phrase. */
    private static final Pattern NULL_INPUT_BEFORE = Pattern.compile(
            "\\bnull\\s+(?:\\w+\\s+){0,2}input\\b|\\binput\\s+(?:\\w+\\s+){0,2}(?:is\\s+)?null\\b",
            Pattern.CASE_INSENSITIVE);
    /** "If the mapping function returns null, ...": a condition, not what the method returns. */
    private static final Pattern IN_CONDITION = Pattern.compile("\\b(?:if|unless|when)\\b[^,;:]*$",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_DEPTH = 4;

    private final ClassLookup classes;
    private final Map<String, Optional<CompilationUnit>> parsed = new HashMap<>();

    /** Where classes and their sources come from. */
    interface ClassLookup {
        Optional<ClassNode> classNode(String internalName);

        Optional<String> source(String internalName);
    }

    JavadocNullness(ClassLookup classes) {
        this.classes = classes;
    }

    /** Whether the Javadoc says the method can return null; empty when it says nothing either way. */
    Optional<Boolean> documentsNull(String owner, MethodNode method) {
        return documentsNull(owner, method.name, method.desc, 0);
    }

    private Optional<Boolean> documentsNull(String owner, String name, String descriptor, int depth) {
        Optional<Javadoc> javadoc = findMethod(owner, name, descriptor).flatMap(MethodDeclaration::getJavadoc);
        if (javadoc.isPresent() && !javadoc.get().toText().contains("{@inheritDoc}")) {
            return Optional.of(saysNull(javadoc.get()));
        }
        if (depth >= MAX_DEPTH) {
            return Optional.empty();
        }
        // the documentation of the overridden method
        Optional<ClassNode> type = classes.classNode(owner);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        List<String> supertypes = new ArrayList<>(type.get().interfaces);
        if (type.get().superName != null) {
            supertypes.add(type.get().superName);
        }
        for (String supertype : supertypes) {
            Optional<Boolean> inherited = documentsNull(supertype, name, descriptor, depth + 1);
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        return Optional.empty();
    }

    static boolean saysNull(Javadoc javadoc) {
        StringBuilder text = new StringBuilder(javadoc.getDescription().toText());
        for (JavadocBlockTag tag : javadoc.getBlockTags()) {
            if (tag.getType() == JavadocBlockTag.Type.RETURN) {
                text.append(' ').append(tag.getContent().toText());
            }
        }
        return saysNull(text.toString());
    }

    static boolean saysNull(String text) {
        String plain = MARKUP.matcher(text).replaceAll("$1").replaceAll("\\s+", " ");
        Matcher matcher = NULLABLE.matcher(plain);
        while (matcher.find()) {
            String before = plain.substring(Math.max(0, matcher.start() - 20), matcher.start());
            String sentence = plain.substring(Math.max(0, plain.lastIndexOf('.', matcher.start()) + 1), matcher.start());
            String after = plain.substring(matcher.start(), Math.min(plain.length(), matcher.end() + 40));
            if (!NEGATED.matcher(before).find() && !FOR_NULL_INPUT.matcher(after).find()
                    && !NULL_INPUT_BEFORE.matcher(sentence).find() && !IN_CONDITION.matcher(sentence).find()) {
                return true;
            }
        }
        return false;
    }

    private Optional<MethodDeclaration> findMethod(String owner, String name, String descriptor) {
        String topLevel = owner.contains("$") ? owner.substring(0, owner.indexOf('$')) : owner;
        Optional<CompilationUnit> unit = parsed.computeIfAbsent(topLevel, top -> classes.source(top)
                .flatMap(source -> new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
                        .parse(source).getResult()));
        if (unit.isEmpty()) {
            return Optional.empty();
        }
        String[] names = owner.substring(owner.lastIndexOf('/') + 1).split("\\$");
        Optional<TypeDeclaration<?>> type = unit.get().getTypes().stream()
                .filter(t -> t.getNameAsString().equals(names[0]))
                .findFirst()
                .map(t -> (TypeDeclaration<?>) t);
        for (int i = 1; i < names.length && type.isPresent(); i++) {
            String nested = names[i];
            type = type.get().getMembers().stream()
                    .filter(BodyDeclaration::isTypeDeclaration)
                    .map(BodyDeclaration::asTypeDeclaration)
                    .filter(t -> t.getNameAsString().equals(nested))
                    .findFirst()
                    .map(t -> (TypeDeclaration<?>) t);
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        List<MethodDeclaration> candidates = type.map(t -> t.getMethodsByName(name)).orElse(List.of()).stream()
                .filter(method -> method.getParameters().size() == arguments.length)
                .toList();
        if (candidates.size() <= 1) {
            return candidates.stream().findFirst();
        }
        return candidates.stream().filter(method -> sameParameters(method, arguments)).findFirst();
    }

    /** Compares simple type names, which is enough to tell overloads apart. */
    private static boolean sameParameters(MethodDeclaration method, Type[] arguments) {
        for (int i = 0; i < arguments.length; i++) {
            Parameter parameter = method.getParameter(i);
            String declared = parameter.getType().asString().replaceAll("<.*>", "") + (parameter.isVarArgs() ? "[]" : "");
            declared = declared.substring(declared.lastIndexOf('.') + 1);
            String compiled = arguments[i].getClassName();
            compiled = compiled.substring(Math.max(compiled.lastIndexOf('.'), compiled.lastIndexOf('$')) + 1);
            if (!declared.equals(compiled)) {
                return false;
            }
        }
        return true;
    }
}
