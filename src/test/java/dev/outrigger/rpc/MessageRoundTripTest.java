package dev.outrigger.rpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MessageRoundTripTest {

    @Test
    void contentLengthCountsBytesNotCharacters() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageWriter writer = new MessageWriter(out);
        writer.write("{\"text\":\"Grüße 👋\"}");
        writer.write("{\"id\":2}");

        MessageReader reader = new MessageReader(new ByteArrayInputStream(out.toByteArray()));
        assertEquals("{\"text\":\"Grüße 👋\"}", new String(reader.read(), StandardCharsets.UTF_8));
        assertEquals("{\"id\":2}", new String(reader.read(), StandardCharsets.UTF_8));
        assertNull(reader.read());
    }

    @Test
    void acceptsExtraHeadersInAnyCase() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String framed = "content-length: 2\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n{}";
        MessageReader reader = new MessageReader(new ByteArrayInputStream(framed.getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(body, reader.read());
    }
}
