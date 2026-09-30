package dev.outrigger.proxy;

import com.google.gson.JsonElement;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * What a {@link Feature} can do with the wrapped server on its own.
 */
public interface ServerAccess {

    /**
     * Sends a request to the server. The response goes to the feature, not to
     * the editor. Completes exceptionally when the server answers with an error.
     */
    CompletableFuture<JsonElement> request(String method, JsonElement params);

    /**
     * Rewrites the diagnostics the server last published for the matching
     * documents again and sends them to the editor, e.g. once a feature knows
     * more than it did when they arrived.
     */
    void republish(Predicate<String> uri);
}
