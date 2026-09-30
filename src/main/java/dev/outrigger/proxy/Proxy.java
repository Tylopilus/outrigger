package dev.outrigger.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.outrigger.document.DocumentStore;
import dev.outrigger.document.PositionEncoding;
import dev.outrigger.document.TextDocument;
import dev.outrigger.rpc.Message;
import dev.outrigger.rpc.MessageReader;
import dev.outrigger.rpc.MessageWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

/**
 * Sits between an editor (the client) and a language server, forwarding every
 * message and letting {@link Feature}s rewrite what the server publishes.
 *
 * <p>Messages are forwarded byte-for-byte unless a feature changes them, so
 * server-specific extensions pass through untouched.
 */
public final class Proxy {

    private final MessageReader fromClient;
    private final MessageWriter toClient;
    private final MessageReader fromServer;
    private final MessageWriter toServer;
    private final List<Feature> features;
    private final DocumentStore documents = new DocumentStore();

    private volatile JsonElement initializeId;

    public Proxy(InputStream clientIn, OutputStream clientOut, InputStream serverIn, OutputStream serverOut,
            List<Feature> features) {
        this.fromClient = new MessageReader(clientIn);
        this.toClient = new MessageWriter(clientOut);
        this.fromServer = new MessageReader(serverIn);
        this.toServer = new MessageWriter(serverOut);
        this.features = features;
    }

    public DocumentStore documents() {
        return documents;
    }

    /**
     * Runs until the server closes its output. When the client disconnects the
     * server's input is closed, so a well-behaved server exits as well.
     */
    public void run() throws InterruptedException {
        Thread clientPump = Thread.ofPlatform().name("outrigger-client").daemon().start(this::pumpClient);
        pumpServer();
        clientPump.interrupt();
    }

    private void pumpClient() {
        try {
            byte[] body;
            while ((body = fromClient.read()) != null) {
                observeClient(body);
                toServer.write(body);
            }
        } catch (IOException e) {
            Log.error("reading from client failed", e);
        } finally {
            try {
                toServer.close();
            } catch (IOException ignored) {
                // server already gone
            }
        }
    }

    private void pumpServer() {
        try {
            byte[] body;
            while ((body = fromServer.read()) != null) {
                toClient.write(rewriteServer(body));
            }
        } catch (IOException e) {
            Log.error("reading from server failed", e);
        }
    }

    private void observeClient(byte[] body) {
        try {
            Message message = Message.parse(body);
            String method = message.method();
            if (method == null) {
                return;
            }
            switch (method) {
                case "initialize" -> initializeId = message.id();
                case "textDocument/didOpen" -> documents.didOpen(message.params());
                case "textDocument/didChange" -> documents.didChange(message.params());
                case "textDocument/didClose" -> documents.didClose(message.params());
                default -> {
                }
            }
        } catch (RuntimeException e) {
            Log.error("could not inspect client message", e);
        }
    }

    private byte[] rewriteServer(byte[] body) {
        try {
            Message message = Message.parse(body);
            if (message.isResponse() && message.id().equals(initializeId)) {
                observeInitializeResult(message.json());
            } else if ("textDocument/publishDiagnostics".equals(message.method())) {
                return rewriteDiagnostics(message).orElse(body);
            }
        } catch (RuntimeException e) {
            Log.error("could not rewrite server message", e);
        }
        return body;
    }

    private void observeInitializeResult(JsonObject response) {
        JsonObject result = response.has("result") && response.get("result").isJsonObject()
                ? response.getAsJsonObject("result")
                : new JsonObject();
        JsonObject capabilities = result.has("capabilities") ? result.getAsJsonObject("capabilities") : new JsonObject();
        String encoding = capabilities.has("positionEncoding") ? capabilities.get("positionEncoding").getAsString() : null;
        documents.setEncoding(PositionEncoding.fromLsp(encoding));
    }

    private Optional<byte[]> rewriteDiagnostics(Message message) {
        JsonObject params = message.params();
        String uri = params.get("uri").getAsString();
        boolean open = documents.isOpen(uri);
        JsonArray diagnostics = params.getAsJsonArray("diagnostics");

        List<Feature> interested = features.stream().filter(f -> f.wantsDiagnostics(diagnostics, open)).toList();
        if (interested.isEmpty()) {
            return Optional.empty();
        }
        Optional<TextDocument> document = documents.getOrRead(uri);
        if (document.isEmpty() || isStale(params, document.get())) {
            return Optional.empty();
        }

        JsonArray rewritten = diagnostics;
        for (Feature feature : interested) {
            try {
                rewritten = feature.diagnostics(document.get(), rewritten, documents.encoding());
            } catch (RuntimeException e) {
                Log.error(feature.getClass().getSimpleName() + " failed on " + uri, e);
            }
        }
        if (rewritten == diagnostics) {
            return Optional.empty();
        }
        JsonObject json = message.json().deepCopy();
        json.getAsJsonObject("params").add("diagnostics", rewritten);
        return Optional.of(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Diagnostics for an older version than the one we hold would point at the wrong text. */
    private static boolean isStale(JsonObject params, TextDocument document) {
        return params.has("version") && !params.get("version").isJsonNull() && document.version() >= 0
                && params.get("version").getAsInt() != document.version();
    }
}
