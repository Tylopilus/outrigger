package dev.outrigger.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.outrigger.TestJars;
import dev.outrigger.library.LibraryNullness.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A small library compiled at test time: an API jar with its sources jar (as
 * in a Maven repository), and an implementation jar in a separate directory
 * (as in the bundles of a local AEM).
 */
class LibraryNullnessTest {

    private static final String API = """
            package lib;

            public interface Api {
                /** @return the thing or {@code null} if it is missing */
                String documented();

                String undocumented();

                String neverNull();

                /** @return the value, never {@code null} */
                String promised();

                @Nullable
                String annotated();
            }
            """;

    private static final String NULLABLE = """
            package lib;

            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            public @interface Nullable {
            }
            """;

    private static final String UTIL = """
            package lib;

            import java.util.Map;

            public class Util {
                public static String returnsNull() { return null; }
                public static String viaCall() { return returnsNull(); }
                public static String checked(Map<String, String> m, String k) {
                    String v = m.get(k);
                    if (v == null) {
                        return "x";
                    }
                    return v;
                }
                public static String unchecked(Map<String, String> m, String k) { return m.get(k); }
                public static String ternary(String s) { return s.isEmpty() ? null : s; }
                public static String parameter(String s) { return s; }
                public static String caught(String s) {
                    try {
                        return s.trim();
                    } catch (RuntimeException e) {
                        return null;
                    }
                }
                public static String defaulted(String[] values) {
                    String result = null;
                    for (String value : values) {
                        if (value != null) {
                            result = value;
                        }
                    }
                    return result == null ? "" : result;
                }
                public static String overridden() { return null; }
                public static String nullForNull(String s) {
                    if (s == null) {
                        return null;
                    }
                    return s.trim();
                }
            }
            """;

    private static final String IMPL = """
            package lib.impl;

            public class ApiImpl implements lib.Api {
                public String documented() { return "d"; }
                public String undocumented() { return null; }
                public String neverNull() { return "x"; }
                public String promised() { return null; }
                public String annotated() { return null; }
            }
            """;

    /** Another vendor's implementation, e.g. a test mock: doesn't count. */
    private static final String FOREIGN = """
            package other;

            public class Mock implements lib.Api {
                public String documented() { return null; }
                public String undocumented() { return null; }
                public String neverNull() { return null; }
                public String promised() { return null; }
                public String annotated() { return null; }
            }
            """;

    private static LibraryNullness nullness;

    @BeforeAll
    static void compileLibrary(@TempDir Path dir) throws IOException {
        Path repository = dir.resolve("m2/lib/1.0");
        Path bundles = dir.resolve("bundles");
        Map<String, String> api = Map.of("lib/Api.java", API, "lib/Nullable.java", NULLABLE, "lib/Util.java", UTIL);
        Path apiJar = TestJars.jar(TestJars.compile(dir.resolve("api"), api, List.of()), repository.resolve("lib-1.0.jar"));
        TestJars.sourcesJar(api, repository.resolve("lib-1.0-sources.jar"));
        TestJars.jar(TestJars.compile(dir.resolve("impl"), Map.of("lib/impl/ApiImpl.java", IMPL), List.of(apiJar)),
                bundles.resolve("bundle0/bundle.jar"));
        TestJars.jar(TestJars.compile(dir.resolve("foreign"), Map.of("other/Mock.java", FOREIGN), List.of(apiJar)),
                bundles.resolve("bundle1/bundle.jar"));

        ImplementationIndex index = ImplementationIndex.load(List.of(bundles), dir.resolve("cache"));
        Settings settings = new Settings(List.of(bundles), Map.of("lib.Util#overridden", false), dir.resolve("cache"));
        nullness = new LibraryNullness(new ClassFiles(List.of(apiJar)), () -> Optional.of(index), settings);
    }

    @Test
    void contractFromJavadoc() {
        assertVerdict(Kind.NULLABLE, "documented", "lib/Api", "documented");
    }

    @Test
    void codeOfTheImplementation() {
        assertVerdict(Kind.NULLABLE, "ApiImpl returns null", "lib/Api", "undocumented");
        assertVerdict(Kind.NOT_NULLABLE, "", "lib/Api", "neverNull");
    }

    @Test
    void onlyTheVendorsOwnImplementationsCount() {
        // other.Mock returns null everywhere, lib.impl.ApiImpl doesn't here
        assertVerdict(Kind.NOT_NULLABLE, "", "lib/Api", "neverNull");
        assertTrue(LibraryNullness.sameVendor("com/day/cq/wcm/api/PageManager", "com/day/cq/wcm/core/impl/PageManagerImpl"));
        assertEquals(false, LibraryNullness.sameVendor("com/day/cq/wcm/api/PageManager", "org/apache/sling/Mock"));
    }

    @Test
    void implementationThatBreaksItsContractCounts() {
        assertVerdict(Kind.NULLABLE, "ApiImpl returns null", "lib/Api", "promised");
    }

    @Test
    void annotatedMethodsAreLeftToJdtls() {
        assertVerdict(Kind.ANNOTATED, "annotated", "lib/Api", "annotated");
    }

    @Test
    void codeOfConcreteMethods() {
        assertVerdict(Kind.NULLABLE, "Util returns null", "lib/Util", "returnsNull");
        assertVerdict(Kind.NULLABLE, "Util returns null", "lib/Util", "viaCall");
        assertVerdict(Kind.NULLABLE, "Util returns null", "lib/Util", "ternary");
        assertVerdict(Kind.NULLABLE, "Util returns null", "lib/Util", "caught");
        assertVerdict(Kind.NOT_NULLABLE, "", "lib/Util", "parameter");
        assertVerdict(Kind.NOT_NULLABLE, "", "lib/Util", "nullForNull"); // null only for null input
    }

    @Test
    void nullChecksInTheCodeAreFollowed() {
        assertVerdict(Kind.NOT_NULLABLE, "", "lib/Util", "checked");
        assertVerdict(Kind.NOT_NULLABLE, "", "lib/Util", "defaulted");
    }

    @Test
    void jdkOnlyByContract() {
        // Locale.of's code returns what an internal cache returns, which can be null on paths never taken
        assertVerdict(Kind.NOT_NULLABLE, "", "java/util/Locale", "of");
    }

    @Test
    void jdkContractFromItsSources() {
        assertTrue(Files.isRegularFile(Path.of(System.getProperty("java.home"), "lib", "src.zip")),
                "needs the JDK's lib/src.zip");
        assertVerdict(Kind.NULLABLE, "documented", "java/util/Map", "get");
        assertVerdict(Kind.NULLABLE, "Util returns the result of Map.get(), documented to return null", "lib/Util",
                "unchecked");
    }

    @Test
    void overrideWins() {
        assertVerdict(Kind.NOT_NULLABLE, "", "lib/Util", "overridden");
    }

    @Test
    void javadocPhrases() {
        assertTrue(JavadocNullness.saysNull("the page or <code>null</code>"));
        assertTrue(JavadocNullness.saysNull("a Tag object or {@code null} if the tag does not exist"));
        assertTrue(JavadocNullness.saysNull("Returns null if nothing was found."));
        assertEquals(false, JavadocNullness.saysNull("the value, never {@code null}"));
        assertEquals(false, JavadocNullness.saysNull("This method does not return null."));
        assertEquals(false, JavadocNullness.saysNull("a value map"));
        assertEquals(false, JavadocNullness.saysNull("@param key must not be null"));
        assertEquals(false, JavadocNullness.saysNull("an array of parsed Strings, {@code null} if null String input"));
        assertEquals(false, JavadocNullness.saysNull("the trimmed string, or null if the input string is null"));
        assertEquals(false, JavadocNullness.saysNull("Splits the text. A {@code null} input String returns {@code null}."));
        assertEquals(false, JavadocNullness.saysNull("If the mapping function returns null, no mapping is recorded."));
        assertTrue(JavadocNullness.saysNull("If the key is unknown, this returns null."));
        assertTrue(JavadocNullness.saysNull("the rendition or <code>null</code> if it does not exist"));
    }

    private static void assertVerdict(Kind kind, String reason, String owner, String name) {
        String descriptor = switch (name) {
            case "checked", "unchecked" -> "(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/String;";
            case "ternary", "parameter", "caught", "nullForNull" -> "(Ljava/lang/String;)Ljava/lang/String;";
            case "defaulted" -> "([Ljava/lang/String;)Ljava/lang/String;";
            case "get" -> "(Ljava/lang/Object;)Ljava/lang/Object;";
            case "of" -> "(Ljava/lang/String;)Ljava/util/Locale;";
            default -> "()Ljava/lang/String;";
        };
        assertEquals(new LibraryNullness.Verdict(kind, reason), nullness.of(owner, name, descriptor), owner + "." + name);
    }
}
