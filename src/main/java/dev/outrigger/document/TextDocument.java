package dev.outrigger.document;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The content of one document at one version, with LSP position conversion.
 * Immutable: applying a change returns a new instance.
 */
public final class TextDocument {

    private final String uri;
    private final int version;
    private final String text;
    private final int[] lineStarts;

    public TextDocument(String uri, int version, String text) {
        this.uri = uri;
        this.version = version;
        this.text = text;
        this.lineStarts = lineStarts(text);
    }

    public String uri() {
        return uri;
    }

    public int version() {
        return version;
    }

    public String text() {
        return text;
    }

    public int lineCount() {
        return lineStarts.length;
    }

    /** The text of a 0-based line, without its line terminator. */
    public String line(int line) {
        if (line < 0 || line >= lineStarts.length) {
            return "";
        }
        int start = lineStarts[line];
        int end = line + 1 < lineStarts.length ? lineStarts[line + 1] : text.length();
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(start, end);
    }

    /** Converts an LSP position into an offset into {@link #text()}. */
    public int offsetAt(int line, int character, PositionEncoding encoding) {
        if (line >= lineStarts.length) {
            return text.length();
        }
        return lineStarts[line] + encoding.toCharIndex(line(line), character);
    }

    /**
     * Applies one {@code TextDocumentContentChangeEvent}: a range edit, or a full
     * replacement when it has no range.
     */
    public TextDocument apply(JsonObject change, int newVersion, PositionEncoding encoding) {
        String newText = change.get("text").getAsString();
        if (!change.has("range")) {
            return new TextDocument(uri, newVersion, newText);
        }
        JsonObject range = change.getAsJsonObject("range");
        int start = offsetAt(range.getAsJsonObject("start"), encoding);
        int end = offsetAt(range.getAsJsonObject("end"), encoding);
        return new TextDocument(uri, newVersion, text.substring(0, start) + newText + text.substring(end));
    }

    private int offsetAt(JsonObject position, PositionEncoding encoding) {
        return offsetAt(position.get("line").getAsInt(), position.get("character").getAsInt(), encoding);
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                i++;
                starts.add(i + 1);
            } else if (c == '\n' || c == '\r') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
