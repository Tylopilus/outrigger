package dev.outrigger.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class TextDocumentTest {

    private static JsonObject change(int startLine, int startChar, int endLine, int endChar, String text) {
        JsonObject start = new JsonObject();
        start.addProperty("line", startLine);
        start.addProperty("character", startChar);
        JsonObject end = new JsonObject();
        end.addProperty("line", endLine);
        end.addProperty("character", endChar);
        JsonObject range = new JsonObject();
        range.add("start", start);
        range.add("end", end);
        JsonObject change = new JsonObject();
        change.add("range", range);
        change.addProperty("text", text);
        return change;
    }

    @Test
    void appliesRangeEditsAcrossLines() {
        TextDocument doc = new TextDocument("file:///a", 1, "one\ntwo\nthree");
        doc = doc.apply(change(0, 1, 2, 2, "X"), 2, PositionEncoding.UTF16);
        assertEquals("oXree", doc.text());
        assertEquals(2, doc.version());
    }

    @Test
    void fullReplacementWithoutRange() {
        TextDocument doc = new TextDocument("file:///a", 1, "old");
        doc = doc.apply(JsonParser.parseString("{\"text\": \"new\"}").getAsJsonObject(), 2, PositionEncoding.UTF16);
        assertEquals("new", doc.text());
    }

    @Test
    void positionsFollowTheNegotiatedEncoding() {
        // "ü" is 1 UTF-16 unit but 2 UTF-8 bytes; "👋" is 2 UTF-16 units, 4 UTF-8 bytes, 1 code point
        TextDocument doc = new TextDocument("file:///a", 1, "a👋ü b");
        assertEquals("a👋ü X", doc.apply(change(0, 5, 0, 6, "X"), 2, PositionEncoding.UTF16).text());
        assertEquals("a👋ü X", doc.apply(change(0, 8, 0, 9, "X"), 2, PositionEncoding.UTF8).text());
        assertEquals("a👋ü X", doc.apply(change(0, 4, 0, 5, "X"), 2, PositionEncoding.UTF32).text());
    }

    @Test
    void linesSplitOnAllTerminators() {
        TextDocument doc = new TextDocument("file:///a", 1, "a\r\nb\rc\nd");
        assertEquals(4, doc.lineCount());
        assertEquals("b", doc.line(1));
        assertEquals("c", doc.line(2));
        assertEquals(PositionEncoding.UTF8.fromCharIndex("ü", 1), 2);
    }
}
