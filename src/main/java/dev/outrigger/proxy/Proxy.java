package dev.outrigger.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Sits between an editor (the client) and a language server, forwarding every
 * message and letting {@link Feature}s rewrite what the server publishes.
 *
 * <p>Messages are forwarded byte-for-byte unless a feature changes them, so
 * server-specific extensions pass through untouched.
 */
public final class Proxy implements ServerAccess {

    private static final String REQUEST_ID_PREFIX = "outrigger-";

    private final MessageReader fromClient;
    private final MessageWriter toClient;
    private final MessageReader fromServer;
    private final MessageWriter toServer;
    private final List<Feature> features;
    private final DocumentStore documents = new DocumentStore();

    private volatile JsonElement initializeId;

    private final AtomicInteger requestIds = new AtomicInteger();
    private final Map<String, CompletableFuture<JsonElement>> pendingRequests = new ConcurrentHashMap<>();

    /** The last publishDiagnostics message from the server per document, for {@link #republish}. */
    private final Map<String, Message> lastDiagnostics = new HashMap<>();
    private final Object diagnosticsLock = new Object();

    public Proxy(InputStream clientIn, OutputStream clientOut, InputStream serverIn, OutputStream serverOut,
            List<Feature> features) {
        this.fromClient = new MessageReader(clientIn);
        this.toClient = new MessageWriter(clientOut);
        this.fromServer = new MessageReader(serverIn);
        this.toServer = new MessageWriter(serverOut);
        this.features = features;
        features.forEach(feature -> feature.attach(this));
    }

    @Override
    public CompletableFuture<JsonElement> request(String method, JsonElement params) {
        String id = REQUEST_ID_PREFIX + requestIds.incrementAndGet();
        CompletableFuture<JsonElement> response = new CompletableFuture<>();
        pendingRequests.put(id, response);
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params);
        try {
            toServer.write(message.toString());
        } catch (IOException e) {
            pendingRequests.remove(id);
            response.completeExceptionally(e);
        }
        return response;
    }

    @Override
    public void republish(Predicate<String> uri) {
        synchronized (diagnosticsLock) {
            for (Map.Entry<String, Message> entry : lastDiagnostics.entrySet()) {
                if (uri.test(entry.getKey())) {
                    try {
                        toClient.write(rewriteDiagnostics(entry.getValue()).orElse(entry.getValue().raw()));
                    } catch (IOException | RuntimeException e) {
                        Log.error("republishing diagnostics failed", e);
                    }
                }
            }
        }
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
                handleServer(body);
            }
        } catch (IOException e) {
            Log.error("reading from server failed", e);
        } finally {
            pendingRequests.values().forEach(r -> r.completeExceptionally(new IOException("server exited")));
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

    private void handleServer(byte[] body) throws IOException {
        Message message;
        try {
            message = Message.parse(body);
        } catch (RuntimeException e) {
            Log.error("could not parse server message", e);
            toClient.write(body);
            return;
        }
        if (message.isResponse() && isOwnRequest(message.id())) {
            completeOwnRequest(message);
            return; // the editor never sent this request
        }
        if ("textDocument/publishDiagnostics".equals(message.method())) {
            synchronized (diagnosticsLock) {
                lastDiagnostics.put(message.params().get("uri").getAsString(), message);
                toClient.write(rewriteSafely(message));
            }
            return;
        }
        if (message.isResponse() && message.id().equals(initializeId)) {
            try {
                observeInitializeResult(message.json());
            } catch (RuntimeException e) {
                Log.error("could not read the initialize result", e);
            }
        }
        toClient.write(body);
    }

    private static boolean isOwnRequest(JsonElement id) {
        return id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                && id.getAsString().startsWith(REQUEST_ID_PREFIX);
    }

    private void completeOwnRequest(Message message) {
        CompletableFuture<JsonElement> response = pendingRequests.remove(message.id().getAsString());
        if (response == null) {
            return;
        }
        JsonObject json = message.json();
        if (json.has("error")) {
            response.completeExceptionally(new ServerError(json.get("error").toString()));
        } else {
            response.complete(json.has("result") ? json.get("result") : JsonNull.INSTANCE);
        }
    }

    private byte[] rewriteSafely(Message message) {
        try {
            return rewriteDiagnostics(message).orElse(message.raw());
        } catch (RuntimeException e) {
            Log.error("could not rewrite diagnostics", e);
            return message.raw();
        }
    }

    /** An error response to a request Outrigger sent itself. */
    public static final class ServerError extends RuntimeException {
        ServerError(String error) {
            super(error);
        }
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
