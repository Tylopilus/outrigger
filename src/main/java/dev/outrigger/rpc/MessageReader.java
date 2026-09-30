package dev.outrigger.rpc;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads LSP base-protocol messages: {@code Content-Length} header, blank line, JSON body.
 * Bodies are returned as raw bytes so they can be forwarded unchanged.
 */
public final class MessageReader {

    private static final String CONTENT_LENGTH = "content-length:";

    private final InputStream in;

    public MessageReader(InputStream in) {
        this.in = in;
    }

    /**
     * Returns the next message body, or {@code null} at end of stream.
     */
    public byte[] read() throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readHeaderLine();
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                if (contentLength >= 0) {
                    break;
                }
                continue; // tolerate stray blank lines between messages
            }
            if (line.toLowerCase().startsWith(CONTENT_LENGTH)) {
                contentLength = Integer.parseInt(line.substring(CONTENT_LENGTH.length()).trim());
            }
        }
        return in.readNBytes(contentLength);
    }

    private String readHeaderLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (line.size() == 0) {
                    return null;
                }
                throw new EOFException("Stream ended inside a message header");
            }
            if (b == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            line.write(b);
        }
    }
}
