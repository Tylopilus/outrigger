package dev.outrigger.library;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeAnnotationNode;

/**
 * Decides whether a library method (one without source in the project) can
 * return null. It can if any of these says so:
 *
 * <ol>
 * <li>an override in {@code nullness.txt} (which also decides the opposite),</li>
 * <li>its annotations ({@code @Nullable}; {@code @NotNull} says the opposite),</li>
 * <li>its contract: the Javadoc, from the sources jar,</li>
 * <li>its code: the bytecode returns null or the result of such a method;
 * for interfaces and abstract methods, the code of their implementations in
 * the {@link ImplementationIndex}.</li>
 * </ol>
 *
 * <p>For the JDK, {@code javax} and {@code jakarta}, only the contract counts:
 * their internals return null on paths callers never see.
 *
 * <p>Implementations only count for the method the project calls (not for
 * callbacks called deep inside library code, like the {@code Supplier} of
 * {@code Optional.orElseGet}), only when they come from the interface's own
 * vendor and product (e.g. {@code com/day/cq}), and never for interfaces of
 * the JDK, {@code javax} or {@code jakarta}: those are implemented
 * everywhere, by unrelated classes and test mocks as well.
 */
public final class LibraryNullness {

    public enum Kind {
        /** Nothing says it can return null. */
        NOT_NULLABLE,
        /** Annotated {@code @Nullable}: jdtls reports its unchecked uses itself. */
        ANNOTATED,
        /** Can return null, found out by Outrigger. */
        NULLABLE
    }

    /** Whether a method can return null, and why. */
    public record Verdict(Kind kind, String reason) {
        static final Verdict NOT_NULLABLE = new Verdict(Kind.NOT_NULLABLE, "");

        public boolean mayBeNull() {
            return kind != Kind.NOT_NULLABLE;
        }
    }

    private static final int MAX_DEPTH = 6;
    private static final int MAX_IMPLEMENTATIONS = 20;
    /** Package segments that identify a vendor and product, e.g. {@code com/day/cq}. */
    private static final int VENDOR_SEGMENTS = 3;
    private static final List<String> STANDARD_PACKAGES = List.of("java/", "javax/", "jakarta/", "sun/", "jdk/");

    private final ClassFiles classFiles;
    private final Supplier<Optional<ImplementationIndex>> implementations;
    private final Settings settings;
    private final JavadocNullness javadoc;
    private final Map<String, Verdict> verdicts = new HashMap<>();
    private final Set<String> inProgress = new HashSet<>();
    private int depth;

    public LibraryNullness(ClassFiles classFiles, Supplier<Optional<ImplementationIndex>> implementations,
            Settings settings) {
        this.classFiles = classFiles;
        this.implementations = implementations;
        this.settings = settings;
        this.javadoc = new JavadocNullness(new JavadocNullness.ClassLookup() {
            @Override
            public Optional<ClassNode> classNode(String internalName) {
                return LibraryNullness.this.classNode(internalName);
            }

            @Override
            public Optional<String> source(String internalName) {
                return classFiles.source(internalName);
            }
        });
    }

    /** Whether the method {@code owner.name descriptor}, as called, can return null. */
    public synchronized Verdict of(String owner, String name, String descriptor) {
        int sort = Type.getReturnType(descriptor).getSort();
        if (sort != Type.OBJECT && sort != Type.ARRAY) {
            return Verdict.NOT_NULLABLE;
        }
        // implementations only for the method called by the project, see the class comment
        boolean withImplementations = depth == 0;
        String key = owner + "." + name + descriptor + (withImplementations ? "+implementations" : "");
        Verdict known = verdicts.get(key);
        if (known != null) {
            return known;
        }
        if (depth >= MAX_DEPTH || !inProgress.add(key)) {
            return Verdict.NOT_NULLABLE; // too deep, or recursive: nothing learned here
        }
        depth++;
        try {
            Verdict verdict = decide(owner, name, descriptor, withImplementations);
            verdicts.put(key, verdict);
            return verdict;
        } finally {
            depth--;
            inProgress.remove(key);
        }
    }

    private Verdict decide(String owner, String name, String descriptor, boolean withImplementations) {
        Optional<Declared> declared = declaration(owner, name, descriptor);
        String declaringType = declared.map(d -> d.type().name).orElse(owner);

        Optional<Boolean> override = settings.override(declaringType, name).or(() -> settings.override(owner, name));
        if (override.isPresent()) {
            return override.get() ? new Verdict(Kind.NULLABLE, "nullness.txt") : Verdict.NOT_NULLABLE;
        }
        if (declared.isEmpty()) {
            return Verdict.NOT_NULLABLE;
        }
        MethodNode method = declared.get().method();

        Optional<Boolean> annotated = annotatedNullable(method);
        if (annotated.isPresent()) {
            return annotated.get() ? new Verdict(Kind.ANNOTATED, "annotated") : Verdict.NOT_NULLABLE;
        }
        if (javadoc.documentsNull(declaringType, method).orElse(false)) {
            return new Verdict(Kind.NULLABLE, "documented");
        }
        if (STANDARD_PACKAGES.stream().anyMatch(declaringType::startsWith)) {
            // precisely documented, and internals like caches return null on paths callers never see
            return Verdict.NOT_NULLABLE;
        }
        // An interface's default method is a placeholder its implementations override: judge those instead
        boolean isDefault = (declared.get().type().access & Opcodes.ACC_INTERFACE) != 0;
        if ((method.access & Opcodes.ACC_ABSTRACT) == 0 && !isDefault) {
            return fromCode(declaringType, method, simpleName(declaringType));
        }
        if (!withImplementations) {
            return Verdict.NOT_NULLABLE;
        }
        // An interface or abstract method: the code of its vendor's implementations
        for (String implementation : implementations.get()
                .map(index -> index.implementations(declaringType, MAX_IMPLEMENTATIONS * 5))
                .orElse(List.of())
                .stream()
                .filter(implementation -> sameVendor(declaringType, implementation) && !isAnonymous(implementation))
                .limit(MAX_IMPLEMENTATIONS)
                .toList()) {
            Optional<Declared> implemented = declaration(implementation, name, descriptor)
                    .filter(d -> (d.method().access & Opcodes.ACC_ABSTRACT) == 0
                            && (d.type().access & Opcodes.ACC_INTERFACE) == 0);
            if (implemented.isPresent()) {
                Verdict verdict = fromCode(implemented.get().type().name, implemented.get().method(),
                        simpleName(implemented.get().type().name));
                if (verdict.mayBeNull()) {
                    return verdict;
                }
            }
        }
        return Verdict.NOT_NULLABLE;
    }

    private Verdict fromCode(String type, MethodNode method, String display) {
        BytecodeNullness.Result result = BytecodeNullness.analyze(type, method);
        if (result.returnsNull()) {
            return new Verdict(Kind.NULLABLE, display + " returns null");
        }
        for (BytecodeNullness.Call call : result.returnedCalls()) {
            Verdict returned = of(call.owner(), call.name(), call.descriptor());
            if (returned.mayBeNull()) {
                // name the root cause: the documented or annotated method, or the code that returns null
                String called = simpleName(call.owner()) + "." + call.name() + "()";
                String reason = switch (returned.reason()) {
                    case "documented" -> display + " returns the result of " + called + ", documented to return null";
                    case "annotated" -> display + " returns the result of " + called + ", annotated @Nullable";
                    case "nullness.txt" -> display + " returns the result of " + called + " (nullness.txt)";
                    default -> returned.reason();
                };
                return new Verdict(Kind.NULLABLE, reason);
            }
        }
        return Verdict.NOT_NULLABLE;
    }

    /** The class that declares the method, searched from {@code owner} up through its supertypes. */
    private Optional<Declared> declaration(String owner, String name, String descriptor) {
        Deque<String> queue = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            Optional<ClassNode> node = classNode(type);
            if (node.isEmpty()) {
                continue;
            }
            for (MethodNode method : node.get().methods) {
                if (method.name.equals(name) && method.desc.equals(descriptor)
                        && (method.access & Opcodes.ACC_BRIDGE) == 0) {
                    return Optional.of(new Declared(node.get(), method));
                }
            }
            if (node.get().superName != null) {
                queue.add(node.get().superName);
            }
            queue.addAll(node.get().interfaces);
        }
        return Optional.empty();
    }

    /** Whether two classes share the first package segments, e.g. {@code com/day/cq}. */
    static boolean sameVendor(String type, String other) {
        String[] segments = type.split("/");
        String[] otherSegments = other.split("/");
        int compared = Math.min(VENDOR_SEGMENTS, segments.length - 1);
        if (compared < 1 || otherSegments.length - 1 < compared) {
            return false;
        }
        for (int i = 0; i < compared; i++) {
            if (!segments[i].equals(otherSegments[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAnonymous(String internalName) {
        return internalName.matches(".*\\$\\d+(\\$.*)?");
    }

    private Optional<ClassNode> classNode(String internalName) {
        return classFiles.classNode(internalName)
                .or(() -> implementations.get().flatMap(index -> index.classNode(internalName)));
    }

    /** {@code @Nullable}-like annotations say true, {@code @NotNull}-like ones false. */
    private static Optional<Boolean> annotatedNullable(MethodNode method) {
        List<String> descriptors = new ArrayList<>();
        for (List<AnnotationNode> annotations : List.of(nullToEmpty(method.visibleAnnotations),
                nullToEmpty(method.invisibleAnnotations))) {
            annotations.forEach(annotation -> descriptors.add(annotation.desc));
        }
        for (List<TypeAnnotationNode> annotations : List.of(nullToEmpty(method.visibleTypeAnnotations),
                nullToEmpty(method.invisibleTypeAnnotations))) {
            annotations.forEach(annotation -> descriptors.add(annotation.desc));
        }
        for (String descriptor : descriptors) {
            if (descriptor.endsWith("/Nullable;") || descriptor.endsWith("/CheckForNull;")) {
                return Optional.of(true);
            }
            if (descriptor.endsWith("/NotNull;") || descriptor.endsWith("/Nonnull;") || descriptor.endsWith("/NonNull;")) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    static String simpleName(String internalName) {
        return internalName.substring(Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$')) + 1);
    }

    private record Declared(ClassNode type, MethodNode method) {
    }
}
