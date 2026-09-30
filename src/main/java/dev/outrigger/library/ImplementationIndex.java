package dev.outrigger.library;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/**
 * Which classes implement or extend which types, in the jars of some
 * directories, e.g. the bundles of a local AEM. Library interfaces like
 * {@code PageManager} have no code on the compile classpath; their
 * implementations there do.
 *
 * <p>Scanning hundreds of bundles takes a while, so the index is cached in a
 * file, which is reused as long as the jars (paths, sizes, dates) are the same.
 */
public final class ImplementationIndex {

    private static final int OPEN_ZIPS = 16;

    private final List<Path> jars;
    /** Class → index into {@link #jars}. */
    private final Map<String, Integer> jarOf;
    /** Type → the classes that directly implement or extend it. */
    private final Map<String, List<String>> subtypes;
    /** Classes that can be instantiated: not interfaces, not abstract. */
    private final Set<String> concrete;
    private final Map<String, Optional<ClassNode>> classes = new HashMap<>();
    private final Map<Path, ZipFile> openZips = new LinkedHashMap<>(OPEN_ZIPS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, ZipFile> eldest) {
            if (size() > OPEN_ZIPS) {
                try {
                    eldest.getValue().close();
                } catch (IOException ignored) {
                    // nothing to do
                }
                return true;
            }
            return false;
        }
    };

    private ImplementationIndex(List<Path> jars, Map<String, Integer> jarOf, Map<String, List<String>> subtypes,
            Set<String> concrete) {
        this.jars = jars;
        this.jarOf = jarOf;
        this.subtypes = subtypes;
        this.concrete = concrete;
    }

    /** Builds the index for the jars in {@code dirs}, or reads it from the cache. */
    public static ImplementationIndex load(List<Path> dirs, Path cacheDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(file -> file.toString().endsWith(".jar") && Files.isRegularFile(file))
                            .sorted()
                            .forEach(jars::add);
                }
            }
        }
        Path cache = cacheDir.resolve("implementations-" + fingerprint(jars) + ".tsv");
        if (Files.isRegularFile(cache)) {
            try {
                return read(jars, cache);
            } catch (IOException | RuntimeException e) {
                // broken cache: scan again
            }
        }
        ImplementationIndex index = scan(jars);
        index.write(cache);
        return index;
    }

    /** Concrete classes that implement or extend {@code type}, directly or not; at most {@code limit}. */
    public List<String> implementations(String type, int limit) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(type));
        while (!queue.isEmpty() && found.size() < limit) {
            for (String subtype : subtypes.getOrDefault(queue.poll(), List.of())) {
                if (seen.add(subtype)) {
                    if (concrete.contains(subtype)) {
                        found.add(subtype);
                    }
                    queue.add(subtype);
                }
            }
        }
        return found;
    }

    public synchronized Optional<ClassNode> classNode(String internalName) {
        return classes.computeIfAbsent(internalName, name -> {
            Integer jar = jarOf.get(name);
            if (jar == null) {
                return Optional.empty();
            }
            try {
                ZipFile zip = openZips.get(jars.get(jar));
                if (zip == null) {
                    zip = new ZipFile(jars.get(jar).toFile());
                    openZips.put(jars.get(jar), zip);
                }
                ZipEntry entry = zip.getEntry(name + ".class");
                if (entry == null) {
                    return Optional.empty();
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
                    return Optional.of(node);
                }
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    public int size() {
        return jarOf.size();
    }

    private static ImplementationIndex scan(List<Path> jars) {
        Map<String, Integer> jarOf = new HashMap<>();
        Map<String, List<String>> subtypes = new HashMap<>();
        Set<String> concrete = new HashSet<>();
        for (int i = 0; i < jars.size(); i++) {
            try (ZipFile zip = new ZipFile(jars.get(i).toFile())) {
                for (ZipEntry entry : zip.stream().toList()) {
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        ClassReader reader = new ClassReader(in.readAllBytes());
                        String className = reader.getClassName();
                        if (jarOf.putIfAbsent(className, i) != null) {
                            continue;
                        }
                        if ((reader.getAccess() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0) {
                            concrete.add(className);
                        }
                        if (reader.getSuperName() != null) {
                            subtypes.computeIfAbsent(reader.getSuperName(), k -> new ArrayList<>()).add(className);
                        }
                        for (String implemented : reader.getInterfaces()) {
                            subtypes.computeIfAbsent(implemented, k -> new ArrayList<>()).add(className);
                        }
                    } catch (RuntimeException e) {
                        // unreadable class file
                    }
                }
            } catch (IOException e) {
                // unreadable jar
            }
        }
        return new ImplementationIndex(jars, jarOf, subtypes, concrete);
    }

    /** One line per class: name, jar index, concrete (1/0), supertypes. */
    private void write(Path cache) throws IOException {
        Files.createDirectories(cache.getParent());
        Map<String, List<String>> supertypes = new HashMap<>();
        subtypes.forEach((type, classes) -> classes.forEach(
                c -> supertypes.computeIfAbsent(c, k -> new ArrayList<>()).add(type)));
        Path temporary = cache.resolveSibling(cache.getFileName() + ".tmp");
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Integer> entry : jarOf.entrySet()) {
                writer.write(entry.getKey() + "\t" + entry.getValue() + "\t" + (concrete.contains(entry.getKey()) ? 1 : 0)
                        + "\t" + String.join(",", supertypes.getOrDefault(entry.getKey(), List.of())));
                writer.newLine();
            }
        }
        Files.move(temporary, cache, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static ImplementationIndex read(List<Path> jars, Path cache) throws IOException {
        Map<String, Integer> jarOf = new HashMap<>();
        Map<String, List<String>> subtypes = new HashMap<>();
        Set<String> concrete = new HashSet<>();
        try (Stream<String> lines = Files.lines(cache, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                String[] fields = line.split("\t", -1);
                jarOf.put(fields[0], Integer.parseInt(fields[1]));
                if (fields[2].equals("1")) {
                    concrete.add(fields[0]);
                }
                if (!fields[3].isEmpty()) {
                    for (String supertype : fields[3].split(",")) {
                        subtypes.computeIfAbsent(supertype, k -> new ArrayList<>()).add(fields[0]);
                    }
                }
            });
        }
        return new ImplementationIndex(jars, jarOf, subtypes, concrete);
    }

    private static String fingerprint(List<Path> jars) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path jar : jars) {
                digest.update((jar + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
