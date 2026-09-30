package dev.outrigger.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/**
 * A JSON-RPC message parsed just far enough to route it. The original bytes are
 * kept so unmodified messages are forwarded exactly as received.
 */
public final class Message {

    private final byte[] raw;
    private final JsonObject json;

    private Message(byte[] raw, JsonObject json) {
        this.raw = raw;
        this.json = json;
    }

    public static Message parse(byte[] raw) {
        JsonElement element = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
        return new Message(raw, element.isJsonObject() ? element.getAsJsonObject() : new JsonObject());
    }

    public byte[] raw() {
        return raw;
    }

    public JsonObject json() {
        return json;
    }

    /** The method of a request or notification, or {@code null} for a response. */
    public String method() {
        return json.has("method") ? json.get("method").getAsString() : null;
    }

    /** The id of a request or response, or {@code null} for a notification. */
    public JsonElement id() {
        return json.has("id") && !json.get("id").isJsonNull() ? json.get("id") : null;
    }

    public boolean isResponse() {
        return method() == null && id() != null;
    }

    public JsonObject params() {
        return json.has("params") && json.get("params").isJsonObject() ? json.getAsJsonObject("params") : null;
    }
}
