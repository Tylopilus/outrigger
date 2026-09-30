package dev.outrigger.nullness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.outrigger.TestJars;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import dev.outrigger.library.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Library methods seen from project code: a compiled API jar with its sources
 * jar on the classpath, and an implementation in an implementation directory.
 */
class LibraryIntegrationTest {

    private static final Map<String, String> API = Map.of(
            "lib/Api.java", """
                    package lib;

                    public interface Api {
                        /** @return the page or <code>null</code> */
                        String documented();

                        String undocumented();

                        String safe();

                        @Nullable
                        String annotated();
                    }
                    """,
            "lib/Nullable.java", """
                    package lib;

                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                    public @interface Nullable {
                    }
                    """,
            "lib/Util.java", """
                    package lib;

                    public class Util {
                        public static String returnsNull() { return null; }
                        public static String safe() { return "x"; }
                    }
                    """);

    private static final String IMPL = """
            package lib.impl;

            public class ApiImpl implements lib.Api {
                public String documented() { return "d"; }
                public String undocumented() { return null; }
                public String safe() { return "x"; }
                public String annotated() { return null; }
            }
            """;

    private static final String SERVICE = """
            package app;

            import lib.Api;
            import lib.Util;

            class Service {
                Api api;
                void a() { final var s = api.documented(); s.trim(); } // WARN
                void b() { final var s = api.undocumented(); s.trim(); } // WARN
                void c() { final var s = api.safe(); s.trim(); } // OK
                void d() { final var s = api.annotated(); s.trim(); } // OK: jdtls reports @Nullable itself
                void e() { Util.returnsNull().trim(); } // WARN
                void f() { Util.safe().trim(); } // OK
                void g() { final var s = api.documented(); if (s == null) { return; } s.trim(); } // OK: checked
                void h() { final var map = new java.util.HashMap<String, String>(); map.get("k").trim(); } // WARN
            }
            """;

    @Test
    void libraryMethodsAreCheckedByContractAndCode(@TempDir Path dir) throws IOException, InterruptedException {
        Path apiJar = TestJars.jar(TestJars.compile(dir.resolve("api"), API, List.of()),
                dir.resolve("m2/lib/1.0/lib-1.0.jar"));
        TestJars.sourcesJar(API, dir.resolve("m2/lib/1.0/lib-1.0-sources.jar"));
        Path bundles = dir.resolve("bundles");
        TestJars.jar(TestJars.compile(dir.resolve("impl"), Map.of("lib/impl/ApiImpl.java", IMPL), List.of(apiJar)),
                bundles.resolve("bundle1/bundle.jar"));

        Path sources = dir.resolve("project/src/main/java");
        Path file = sources.resolve("app/Service.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SERVICE);

        Projects.ClasspathSource classpaths = uri -> CompletableFuture.completedFuture(
                Optional.of(new Projects.Classpath(dir.resolve("project"), List.of(apiJar), List.of(sources))));
        NullnessFeature feature = new NullnessFeature(
                new Settings(List.of(bundles), Map.of(), dir.resolve("cache")), classpaths);
        TextDocument document = new TextDocument(file.toUri().toString(), 1, SERVICE);

        // The first analysis starts loading the classpath and the implementations in the background
        Map<Integer, String> warnings = Map.of();
        for (int attempt = 0; attempt < 100 && warnings.size() < 4; attempt++) {
            warnings = warnings(feature.diagnostics(document, new JsonArray(), PositionEncoding.UTF16));
            Thread.sleep(100);
        }

        Map<Integer, String> expected = new TreeMap<>();
        List<String> lines = SERVICE.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).endsWith("// WARN")) {
                expected.put(i + 1, warnings.getOrDefault(i + 1, "<missing>"));
            }
        }
        assertEquals(expected, warnings);
        assertTrue(warnings.get(8).contains("Api.documented() can return null: documented"), warnings.get(8));
        assertTrue(warnings.get(9).contains("Api.undocumented() can return null: ApiImpl returns null"), warnings.get(9));
        assertTrue(warnings.get(12).contains("Util.returnsNull() can return null: Util returns null"), warnings.get(12));
        assertTrue(warnings.get(15).contains("HashMap.get() can return null"), warnings.get(15));
    }

    @Test
    void classpathFromJdtls(@TempDir Path dir) throws IOException {
        Path jar = Files.createDirectories(dir.resolve("m2")).resolve("lib.jar");
        Files.writeString(jar, "");
        Path otherModule = dir.resolve("platform/core");
        Files.createDirectories(otherModule.resolve("src/main/java"));
        String result = """
                {"projectRoot": "%s", "classpaths": ["%s", "%s", "%s", "/gone.jar"]}
                """.formatted(dir.resolve("app").toUri(), jar, otherModule.resolve("target/classes"),
                otherModule.resolve("target/test-classes"));

        Projects.Classpath classpath = JdtlsClasspath.parse(JsonParser.parseString(result)).orElseThrow();

        assertEquals(dir.resolve("app"), classpath.root());
        assertEquals(List.of(jar), classpath.jars());
        assertEquals(List.of(otherModule.resolve("src/main/java")), classpath.sourceRoots());
    }

    private static Map<Integer, String> warnings(JsonArray diagnostics) {
        Map<Integer, String> warnings = new TreeMap<>();
        for (JsonElement element : diagnostics) {
            warnings.put(element.getAsJsonObject().getAsJsonObject("range").getAsJsonObject("start")
                    .get("line").getAsInt() + 1, element.getAsJsonObject().get("message").getAsString());
        }
        return warnings;
    }
}
