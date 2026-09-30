package dev.outrigger.nullness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Cases are Java sources with expectations in end-of-line comments:
 * {@code // DROP a} / {@code // KEEP a} for a jdtls "may be null" warning on
 * {@code a}, {@code // WARN} where Outrigger must report a nullable result and
 * {@code // DEADCODE} where jdtls' dead-code warning must be dropped.
 */
class NullnessAnalysisTest {

    private static final Pattern VERDICT = Pattern.compile("(DROP|KEEP) (\\w+)");

    @Test
    void libraryNullChecksRuleOutJdtlsWarnings() throws IOException {
        String source = resource("LibraryChecks.java.txt");
        List<String> lines = source.lines().toList();
        List<JsonObject> diagnostics = new ArrayList<>();
        List<String> expectations = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = VERDICT.matcher(line);
            while (m.find()) {
                String var = m.group(2);
                diagnostics.add(potentialNull(i, useOf(line, var), var));
                expectations.add(m.group(1) + " " + var + " @L" + (i + 1));
            }
        }

        JsonArray result = analyse(source, toArray(diagnostics), PositionEncoding.UTF16);

        List<String> failures = new ArrayList<>();
        for (int i = 0; i < diagnostics.size(); i++) {
            boolean kept = contains(result, diagnostics.get(i));
            if (kept != expectations.get(i).startsWith("KEEP")) {
                failures.add(expectations.get(i));
            }
        }
        assertEquals(List.of(), failures);
        assertEquals(15, diagnostics.size());
    }

    @Test
    void unannotatedNullableMethodsAreInferred() throws IOException {
        assertNullableResults(resource("NullableResults.java.txt"));
    }

    @Test
    void columnsAreRightWithTabIndentation() throws IOException {
        assertNullableResults(resource("NullableResults.java.txt").replace("    ", "\t"));
    }

    @Test
    void positionsUseTheNegotiatedEncoding() {
        String source = """
                class U {
                    String direct() { return null; }
                    void f() {
                        String s = direct(); log("äöü👋"); s.trim();
                    }
                }
                """;
        String line = source.lines().toList().get(3);
        int sIndex = line.lastIndexOf("s.trim");
        for (PositionEncoding encoding : PositionEncoding.values()) {
            JsonArray result = analyse(source, new JsonArray(), encoding);
            JsonObject start = result.get(0).getAsJsonObject().getAsJsonObject("range").getAsJsonObject("start");
            assertEquals(encoding.fromCharIndex(line, sIndex), start.get("character").getAsInt(), encoding.name());
        }
    }

    @Test
    void unparseableSourceLeavesDiagnosticsAlone() {
        JsonArray diagnostics = toArray(List.of(potentialNull(0, 0, "a")));
        assertEquals(diagnostics, new NullnessFeature().diagnostics(
                new TextDocument("file:///X.java", 1, "class X { void f( {"), diagnostics, PositionEncoding.UTF16));
    }

    private static void assertNullableResults(String source) {
        List<String> lines = source.lines().toList();
        List<JsonObject> jdtls = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("// DEADCODE")) {
                jdtls.add(diagnostic(i, line.indexOf('{'), NullnessAnalysis.DEAD_CODE, "Dead code"));
            }
            if (line.contains("return r != null")) {
                jdtls.add(diagnostic(i, line.indexOf("adapt"), "970", "Null type mismatch"));
                jdtls.add(diagnostic(i, line.indexOf("null;"), "969", "Null type mismatch"));
            }
        }

        JsonArray result = analyse(source, toArray(jdtls), PositionEncoding.UTF16);

        Map<Integer, String> warnings = new TreeMap<>();
        for (JsonElement element : result) {
            JsonObject d = element.getAsJsonObject();
            assertEquals(NullnessAnalysis.SOURCE, d.get("source").getAsString(), "jdtls diagnostic not dropped: " + d);
            JsonObject start = d.getAsJsonObject("range").getAsJsonObject("start");
            int line = start.get("line").getAsInt();
            warnings.put(line + 1, d.get("message").getAsString());
            // the warning points at the dereferenced expression, e.g. "s" in "s.trim()"
            String text = lines.get(line).substring(start.get("character").getAsInt());
            assertTrue(text.matches("^(s|t|direct\\(\\))\\..*"), "wrong column: " + text);
        }
        Map<Integer, String> expected = new TreeMap<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("// WARN")) {
                expected.put(i + 1, warnings.getOrDefault(i + 1, "<missing>"));
            }
        }
        assertEquals(expected, warnings);
        assertEquals(3, warnings.size());
    }

    private static JsonArray analyse(String source, JsonArray diagnostics, PositionEncoding encoding) {
        return new NullnessFeature().diagnostics(new TextDocument("file:///T.java", 1, source), diagnostics, encoding);
    }

    /** Column of the use of {@code var} a case line is about: its last occurrence, or its dereference. */
    private static int useOf(String line, String var) {
        String code = line.replaceAll("//.*", "");
        if (code.contains("anyNull(a, b) ||")) {
            return code.indexOf(var + ".");
        }
        Matcher m = Pattern.compile("\\b" + var + "\\b").matcher(code);
        int column = -1;
        while (m.find()) {
            column = m.start();
        }
        return column;
    }

    private static JsonObject potentialNull(int line, int character, String var) {
        return diagnostic(line, character, NullnessAnalysis.POTENTIAL_NULL,
                "Potential null pointer access: The variable " + var + " may be null at this location");
    }

    private static JsonObject diagnostic(int line, int character, String code, String message) {
        JsonObject position = new JsonObject();
        position.addProperty("line", line);
        position.addProperty("character", character);
        JsonObject range = new JsonObject();
        range.add("start", position);
        range.add("end", position);
        JsonObject diagnostic = new JsonObject();
        diagnostic.add("range", range);
        diagnostic.addProperty("code", code);
        diagnostic.addProperty("message", message);
        return diagnostic;
    }

    private static JsonArray toArray(List<JsonObject> diagnostics) {
        JsonArray array = new JsonArray();
        diagnostics.forEach(array::add);
        return array;
    }

    private static boolean contains(JsonArray array, JsonObject diagnostic) {
        for (JsonElement element : array) {
            if (element.equals(diagnostic)) {
                return true;
            }
        }
        return false;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = NullnessAnalysisTest.class.getResourceAsStream("/nullness/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
