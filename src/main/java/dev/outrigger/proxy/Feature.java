package dev.outrigger.proxy;

import com.google.gson.JsonArray;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;

/**
 * Something Outrigger adds on top of the wrapped server.
 */
public interface Feature {

    /**
     * Whether this feature wants to look at diagnostics published for a
     * document. Documents that are not open have to be read from disk, so
     * features should only ask for them when the diagnostics are relevant.
     */
    boolean wantsDiagnostics(JsonArray diagnostics, boolean documentOpen);

    /**
     * Rewrites the diagnostics the server published for {@code document}.
     * Returns the list to publish instead, which may be the input unchanged.
     */
    JsonArray diagnostics(TextDocument document, JsonArray diagnostics, PositionEncoding encoding);
}
