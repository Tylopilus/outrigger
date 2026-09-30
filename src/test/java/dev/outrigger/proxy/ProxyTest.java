package dev.outrigger.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.outrigger.nullness.NullnessFeature;
import dev.outrigger.rpc.MessageReader;
import dev.outrigger.rpc.MessageWriter;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ProxyTest {

    private static final String URI = "file:///work/Filter.java";

    @Test
    void forwardsTrafficAndRewritesDiagnosticsForTheCurrentText() throws Exception {
        Pipe clientToProxy = new Pipe();
        Pipe proxyToClient = new Pipe();
        Pipe proxyToServer = new Pipe();
        Pipe serverToProxy = new Pipe();
        Proxy proxy = new Proxy(clientToProxy.in, proxyToClient.out, serverToProxy.in, proxyToServer.out,
                List.of(new NullnessFeature()));
        Thread proxyThread = Thread.ofPlatform().start(() -> {
            try {
                proxy.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        MessageWriter client = new MessageWriter(clientToProxy.out);
        MessageReader clientInbox = new MessageReader(proxyToClient.in);
        MessageWriter server = new MessageWriter(serverToProxy.out);
        MessageReader serverInbox = new MessageReader(proxyToServer.in);

        // handshake passes through byte-for-byte
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        client.write(initialize);
        assertArrayEquals(bytes(initialize), serverInbox.read());
        String initialized = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"capabilities\":{\"positionEncoding\":\"utf-16\"}}}";
        server.write(initialized);
        assertArrayEquals(bytes(initialized), clientInbox.read());

        // open, then insert a line at the top: the proxy must track the edit
        String source = """
                class Filter {
                    boolean f(A a) {
                        if (ObjectUtils.anyNull(a)) {
                            return false;
                        }
                        return a.ok();
                    }
                }
                """;
        client.write(didOpen(source));
        serverInbox.read();
        client.write(didChange("// header\n"));
        serverInbox.read();

        // jdtls warns about `a` in `return a.ok();`, now on line 6, plus something unrelated
        JsonObject unrelated = diagnostic(0, 0, "123", "Something else");
        server.write(publish(diagnostic(6, 15, "536871364",
                "Potential null pointer access: The variable a may be null at this location"), unrelated));
        JsonObject published = JsonParser.parseString(new String(clientInbox.read(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray diagnostics = published.getAsJsonObject("params").getAsJsonArray("diagnostics");
        assertEquals(1, diagnostics.size());
        assertEquals(unrelated, diagnostics.get(0));

        // server extensions are forwarded untouched
        String status = "{ \"jsonrpc\": \"2.0\", \"method\": \"language/status\", \"params\": {\"type\": \"Started\"} }";
        server.write(status);
        assertArrayEquals(bytes(status), clientInbox.read());

        // client disconnects -> server input closes; server exits -> proxy stops
        clientToProxy.out.close();
        assertNull(serverInbox.read());
        serverToProxy.out.close();
        proxyThread.join();
    }

    @Test
    void ownRequestsGoToTheServerAndTheirResponsesNotToTheEditor() throws Exception {
        Pipe clientToProxy = new Pipe();
        Pipe proxyToClient = new Pipe();
        Pipe proxyToServer = new Pipe();
        Pipe serverToProxy = new Pipe();
        java.util.concurrent.atomic.AtomicReference<ServerAccess> access = new java.util.concurrent.atomic.AtomicReference<>();
        Feature feature = new Feature() {
            @Override
            public void attach(ServerAccess server) {
                access.set(server);
            }

            @Override
            public boolean wantsDiagnostics(JsonArray diagnostics, boolean documentOpen) {
                return false;
            }

            @Override
            public JsonArray diagnostics(dev.outrigger.document.TextDocument document, JsonArray diagnostics,
                    dev.outrigger.document.PositionEncoding encoding) {
                return diagnostics;
            }
        };
        Proxy proxy = new Proxy(clientToProxy.in, proxyToClient.out, serverToProxy.in, proxyToServer.out,
                List.of(feature));
        Thread proxyThread = Thread.ofPlatform().start(() -> {
            try {
                proxy.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        MessageWriter server = new MessageWriter(serverToProxy.out);
        MessageReader serverInbox = new MessageReader(proxyToServer.in);
        MessageReader clientInbox = new MessageReader(proxyToClient.in);

        var response = access.get().request("workspace/executeCommand", JsonParser.parseString("{\"command\":\"x\"}"));
        JsonObject request = JsonParser.parseString(new String(serverInbox.read(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("workspace/executeCommand", request.get("method").getAsString());

        server.write("{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id") + ",\"result\":{\"ok\":true}}");
        assertEquals(JsonParser.parseString("{\"ok\":true}"), response.get(5, java.util.concurrent.TimeUnit.SECONDS));

        // the editor only sees what the server sends it afterwards, not the response
        String notification = "{\"jsonrpc\":\"2.0\",\"method\":\"window/logMessage\",\"params\":{}}";
        server.write(notification);
        assertArrayEquals(bytes(notification), clientInbox.read());

        clientToProxy.out.close();
        assertNull(serverInbox.read());
        serverToProxy.out.close();
        proxyThread.join();
    }

    private static String didOpen(String text) {
        JsonObject doc = new JsonObject();
        doc.addProperty("uri", URI);
        doc.addProperty("languageId", "java");
        doc.addProperty("version", 1);
        doc.addProperty("text", text);
        JsonObject params = new JsonObject();
        params.add("textDocument", doc);
        return notification("textDocument/didOpen", params);
    }

    private static String didChange(String inserted) {
        JsonObject doc = new JsonObject();
        doc.addProperty("uri", URI);
        doc.addProperty("version", 2);
        JsonObject change = JsonParser.parseString(
                "{\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":0}}}")
                .getAsJsonObject();
        change.addProperty("text", inserted);
        JsonArray changes = new JsonArray();
        changes.add(change);
        JsonObject params = new JsonObject();
        params.add("textDocument", doc);
        params.add("contentChanges", changes);
        return notification("textDocument/didChange", params);
    }

    private static String publish(JsonObject... diagnostics) {
        JsonArray array = new JsonArray();
        for (JsonObject d : diagnostics) {
            array.add(d);
        }
        JsonObject params = new JsonObject();
        params.addProperty("uri", URI);
        params.add("diagnostics", array);
        return notification("textDocument/publishDiagnostics", params);
    }

    private static JsonObject diagnostic(int line, int character, String code, String message) {
        return JsonParser.parseString("""
                {"range":{"start":{"line":%d,"character":%d},"end":{"line":%d,"character":%d}},
                 "severity":2,"code":"%s","source":"Java","message":"%s"}""".formatted(
                line, character, line, character + 1, code, message)).getAsJsonObject();
    }

    private static String notification(String method, JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        message.add("params", params);
        return message.toString();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Pipe {
        final PipedInputStream in = new PipedInputStream(1 << 20);
        final PipedOutputStream out;

        Pipe() throws IOException {
            out = new PipedOutputStream(in);
        }
    }
}
