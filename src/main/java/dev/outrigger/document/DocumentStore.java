package dev.outrigger.document;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors the documents the editor has open, by following the same
 * {@code textDocument/did*} notifications the server receives.
 */
public final class DocumentStore {

    private final Map<String, TextDocument> open = new ConcurrentHashMap<>();
    private volatile PositionEncoding encoding = PositionEncoding.UTF16;

    public PositionEncoding encoding() {
        return encoding;
    }

    public void setEncoding(PositionEncoding encoding) {
        this.encoding = encoding;
    }

    public void didOpen(JsonObject params) {
        JsonObject doc = params.getAsJsonObject("textDocument");
        String uri = doc.get("uri").getAsString();
        open.put(uri, new TextDocument(uri, doc.get("version").getAsInt(), doc.get("text").getAsString()));
    }

    public void didChange(JsonObject params) {
        JsonObject doc = params.getAsJsonObject("textDocument");
        String uri = doc.get("uri").getAsString();
        int version = doc.get("version").getAsInt();
        open.computeIfPresent(uri, (key, current) -> {
            TextDocument updated = current;
            for (JsonElement change : params.getAsJsonArray("contentChanges")) {
                updated = updated.apply(change.getAsJsonObject(), version, encoding);
            }
            return updated;
        });
    }

    public void didClose(JsonObject params) {
        open.remove(params.getAsJsonObject("textDocument").get("uri").getAsString());
    }

    /** The document if the editor has it open. */
    public Optional<TextDocument> get(String uri) {
        return Optional.ofNullable(open.get(uri));
    }

    public boolean isOpen(String uri) {
        return open.containsKey(uri);
    }

    /** The open document, or the file's content on disk for {@code file:} URIs. */
    public Optional<TextDocument> getOrRead(String uri) {
        TextDocument doc = open.get(uri);
        if (doc != null) {
            return Optional.of(doc);
        }
        try {
            URI parsed = URI.create(uri);
            if (!"file".equals(parsed.getScheme())) {
                return Optional.empty();
            }
            return Optional.of(new TextDocument(uri, -1, Files.readString(Path.of(parsed), StandardCharsets.UTF_8)));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
