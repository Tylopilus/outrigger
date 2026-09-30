package dev.outrigger.rpc;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes LSP base-protocol messages. Thread-safe: messages from different
 * threads are never interleaved.
 */
public final class MessageWriter {

    private final OutputStream out;

    public MessageWriter(OutputStream out) {
        this.out = out;
    }

    public synchronized void write(byte[] body) throws IOException {
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    public void write(String body) throws IOException {
        write(body.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void close() throws IOException {
        out.close();
    }
}
