package dev.outrigger.library;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Class files and their sources, looked up by internal name (e.g.
 * {@code com/day/cq/wcm/api/PageManager}) in a list of jars and in the JDK.
 * Sources come from the jar's {@code -sources.jar} next to it, as Maven
 * stores them, and from the JDK's {@code lib/src.zip}.
 */
public final class ClassFiles {

    private static final int OPEN_ZIPS = 32;

    /** The jar each class is read from; the first jar that has it wins, like on a classpath. */
    private final Map<String, Path> jarOf = new HashMap<>();
    private final Map<String, Optional<ClassNode>> classes = new HashMap<>();
    private final Map<String, Optional<String>> sources = new HashMap<>();
    private final Map<Path, ZipFile> openZips = new LinkedHashMap<>(OPEN_ZIPS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, ZipFile> eldest) {
            if (size() > OPEN_ZIPS) {
                closeQuietly(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private Map<String, String> jdkSources;

    public ClassFiles(List<Path> jars) {
        for (Path jar : jars) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                zip.stream()
                        .map(ZipEntry::getName)
                        .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/")
                                && !name.endsWith("module-info.class"))
                        .forEach(name -> jarOf.putIfAbsent(name.substring(0, name.length() - 6), jar));
            } catch (IOException e) {
                // unreadable jar: its classes stay unknown
            }
        }
    }

    /** The class with its method code, from the jars or the JDK. */
    public synchronized Optional<ClassNode> classNode(String internalName) {
        return classes.computeIfAbsent(internalName, name -> readClass(name).map(bytes -> {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }));
    }

    /** The source file that declares the class (for nested classes: its top-level class). */
    public synchronized Optional<String> source(String internalName) {
        String topLevel = internalName.contains("$") ? internalName.substring(0, internalName.indexOf('$'))
                : internalName;
        return sources.computeIfAbsent(topLevel, this::readSource);
    }

    private Optional<byte[]> readClass(String name) {
        Path jar = jarOf.get(name);
        if (jar != null) {
            return readEntry(jar, name + ".class");
        }
        // The JDK's own classes
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readSource(String topLevel) {
        Path jar = jarOf.get(topLevel);
        if (jar != null) {
            String fileName = jar.getFileName().toString();
            if (!fileName.endsWith(".jar")) {
                return Optional.empty();
            }
            Path sourcesJar = jar.resolveSibling(fileName.substring(0, fileName.length() - 4) + "-sources.jar");
            return Files.isRegularFile(sourcesJar)
                    ? readEntry(sourcesJar, topLevel + ".java").map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                    : Optional.empty();
        }
        String entry = jdkSources().get(topLevel + ".java");
        return entry == null ? Optional.empty()
                : readEntry(jdkSourceZip(), entry).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /** The JDK's src.zip lists files per module ({@code java.base/java/util/Map.java}). */
    private Map<String, String> jdkSources() {
        if (jdkSources == null) {
            jdkSources = new HashMap<>();
            Path zip = jdkSourceZip();
            if (Files.isRegularFile(zip)) {
                try (ZipFile file = new ZipFile(zip.toFile())) {
                    file.stream().map(ZipEntry::getName).filter(name -> name.endsWith(".java"))
                            .forEach(name -> jdkSources.putIfAbsent(name.substring(name.indexOf('/') + 1), name));
                } catch (IOException e) {
                    // no JDK sources
                }
            }
        }
        return jdkSources;
    }

    private static Path jdkSourceZip() {
        return Path.of(System.getProperty("java.home"), "lib", "src.zip");
    }

    private Optional<byte[]> readEntry(Path jar, String entry) {
        try {
            ZipFile zip = openZips.get(jar);
            if (zip == null) {
                zip = new ZipFile(jar.toFile());
                openZips.put(jar, zip);
            }
            ZipEntry found = zip.getEntry(entry);
            if (found == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(found)) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static void closeQuietly(ZipFile zip) {
        try {
            zip.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }
}
