package j1;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyClientTest {
    @Test
    void readsTwoBlocksAndRejectsAShortStream() throws Exception {
        byte[] framed = frame("KEY".getBytes(StandardCharsets.US_ASCII), "CRT".getBytes(StandardCharsets.US_ASCII));
        byte[][] parts = KeyClient.readPair(new ByteArrayInputStream(framed));
        assertEquals("KEY", new String(parts[0], StandardCharsets.US_ASCII));
        assertEquals("CRT", new String(parts[1], StandardCharsets.US_ASCII));
        assertThrows(java.io.IOException.class,
                () -> KeyClient.readPair(new ByteArrayInputStream(new byte[] {0, 0, 0, 4, 1})));
    }

    @Test
    void saveReplacesOnlyAfterBothFilesAndRestoresTheOldKey() throws Exception {
        Path dir = Files.createTempDirectory("j1-out");
        Path key = dir.resolve("alice.key");
        Files.writeString(key, "old");
        Files.createDirectory(dir.resolve("alice.crt"));
        assertThrows(java.io.IOException.class, () -> KeyClient.save(dir, "alice",
                "new-key".getBytes(StandardCharsets.US_ASCII),
                "new-crt".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("old", Files.readString(key));
        assertFalse(Files.exists(dir.resolve("alice.key.part")));
        assertFalse(Files.exists(dir.resolve("alice.key.bak")));

        Files.delete(dir.resolve("alice.crt"));
        KeyClient.save(dir, "alice",
                "new-key".getBytes(StandardCharsets.US_ASCII),
                "new-crt".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals("new-key".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(key));
        assertArrayEquals("new-crt".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(dir.resolve("alice.crt")));
    }

    @Test
    void parsesDelayAndDrop() {
        KeyClient.Command command = KeyClient.parse(new String[] {
                "--host", "127.0.0.1", "--name", "alice", "--port", "9000", "--delay", "2", "--drop"});
        assertEquals("alice", command.name());
        assertEquals("127.0.0.1", command.host());
        assertEquals(9000, command.port());
        assertEquals(2, command.delay());
        assertTrue(command.drop());
        assertThrows(IllegalArgumentException.class,
                () -> KeyClient.parse(new String[] {"--name", "a/b", "--host", "127.0.0.1", "--port", "9000"}));
        assertThrows(IllegalArgumentException.class,
                () -> KeyClient.parse(new String[] {"--name", "alice", "--host", "127.0.0.1", "--port", "9000", "--delay", "-1"}));
    }

    private static byte[] frame(byte[] first, byte[] second) {
        ByteBuffer buf = ByteBuffer.allocate(8 + first.length + second.length);
        buf.putInt(first.length);
        buf.put(first);
        buf.putInt(second.length);
        buf.put(second);
        return buf.array();
    }
}
