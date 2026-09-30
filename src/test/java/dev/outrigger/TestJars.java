package dev.outrigger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.ToolProvider;

/** Compiles Java sources and packs jars, to test against real class files. */
public final class TestJars {

    private TestJars() {
    }

    /** Compiles {@code sources} (path → content) into {@code dir/classes}. */
    public static Path compile(Path dir, Map<String, String> sources, List<Path> classpath) throws IOException {
        Path src = dir.resolve("src");
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
        }
        List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
        if (!classpath.isEmpty()) {
            arguments.addAll(List.of("-cp", classpath.stream().map(Path::toString).collect(Collectors.joining(":"))));
        }
        try (Stream<Path> files = Files.walk(src)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> arguments.add(file.toString()));
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)),
                "compilation failed");
        return classes;
    }

    /** Packs the files under {@code classes} into {@code jar}. */
    public static Path jar(Path classes, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
                Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry(classes.relativize(file).toString()));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return jar;
    }

    /** Packs {@code sources} (path → content) into {@code jar}, like a Maven sources jar. */
    public static Path sourcesJar(Map<String, String> sources, Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> source : sources.entrySet()) {
                out.putNextEntry(new ZipEntry(source.getKey()));
                out.write(source.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
