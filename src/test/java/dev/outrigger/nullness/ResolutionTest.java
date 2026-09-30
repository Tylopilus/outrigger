package dev.outrigger.nullness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Nullable results across nested classes, lambdas, anonymous classes,
 * qualified calls, fields and files. Lines ending in {@code // WARN} must get
 * an Outrigger warning, all other lines must not.
 */
class ResolutionTest {

    private static final String NESTED = """
            package com.example;

            class Outer {
                String outerFind() { return null; }
                String viaLocal() { String r = null; return r; }
                String checkedLocal() { String r = outerFind(); if (r == null) { return ""; } return r; }
                String fieldValue = outerFind();
                String fineField = "x";

                class Inner {
                    String innerFind() { return null; }
                    void a() { String s = innerFind(); s.trim(); } // WARN
                    void b() { String s = outerFind(); s.trim(); } // WARN
                }
                static class Helper {
                    static String find() { return null; }
                    String same() { return "x"; }
                    void d() { String s = same(); s.trim(); } // OK: this class's same() isn't nullable
                }
                static class Other {
                    String same() { return null; }
                }
                void c(Inner inner) { String s = inner.innerFind(); s.trim(); } // WARN
                void e(java.util.List<String> l) { l.forEach(x -> { String s = outerFind(); s.trim(); }); } // WARN
                void f() {
                    String s = outerFind();
                    if (s == null) { return; }
                    Runnable r = new Runnable() { public void run() { s.trim(); } }; // OK: checked before
                }
                void g() { String s = Helper.find(); s.trim(); } // WARN
                void h() { fieldValue.trim(); } // WARN
                void h2() { if (fieldValue != null) { fieldValue.trim(); } } // OK: checked
                void h3() { fineField.trim(); } // OK
                void i() { String s = viaLocal(); s.trim(); } // WARN
                void i2() { String s = checkedLocal(); s.trim(); } // OK: returns a checked local
                void j() { String s = this.outerFind(); s.trim(); } // WARN
                void k() { String s = outerFind(); if (s != null) { s.trim(); } } // OK
            }
            """;

    @Test
    void nestedClassesLambdasAnonymousClassesAndFields(@TempDir Path root) throws IOException {
        Path file = write(root, "com/example/Outer.java", NESTED);
        assertWarnings(file, NESTED);
    }

    @Test
    void methodsInOtherFilesOfTheModule(@TempDir Path root) throws IOException {
        write(root, "com/example/repo/Repo.java", """
                package com.example.repo;

                public class Repo {
                    public String find() { return null; }
                    public String found() { return "x"; }
                    public static String lookup() { return Other.missing(); }
                }
                """);
        write(root, "com/example/repo/Other.java", """
                package com.example.repo;

                class Other {
                    static String missing() { return null; }
                }
                """);
        String service = """
                package com.example;

                import com.example.repo.Repo;

                class Service {
                    Repo repo;
                    void a() { String s = repo.find(); s.trim(); } // WARN
                    void b() { Repo.lookup().trim(); } // WARN
                    void c() { String s = repo.find(); if (s != null) { s.trim(); } } // OK
                    void d() { repo.found().trim(); } // OK
                }
                """;
        assertWarnings(write(root, "com/example/Service.java", service), service);
    }

    private static void assertWarnings(Path file, String source) {
        JsonArray diagnostics = new NullnessFeature().diagnostics(
                new TextDocument(file.toUri().toString(), 1, source), new JsonArray(), PositionEncoding.UTF16);
        Map<Integer, String> actual = new TreeMap<>();
        for (JsonElement element : diagnostics) {
            int line = element.getAsJsonObject().getAsJsonObject("range").getAsJsonObject("start")
                    .get("line").getAsInt() + 1;
            actual.put(line, element.getAsJsonObject().get("message").getAsString());
        }
        List<String> lines = source.lines().toList();
        Map<Integer, String> expected = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).endsWith("// WARN")) {
                expected.put(i + 1, actual.getOrDefault(i + 1, "<missing>"));
            }
        }
        assertEquals(expected, actual);
    }

    private static Path write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
