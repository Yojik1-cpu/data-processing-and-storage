package j1;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyServerTest {
    @Test
    void parsesGenerateAndServe() {
        KeyServer.Command generate = KeyServer.parse(new String[] {"--generate-ca", "ca.key"});
        assertEquals(KeyServer.Mode.GENERATE, generate.mode());
        assertEquals("ca.key", generate.generatePath().toString());

        KeyServer.Command serve = KeyServer.parse(new String[] {
                "--threads", "8", "--ca", "ca.key", "--port", "9000"});
        assertEquals(KeyServer.Mode.SERVE, serve.mode());
        assertEquals(9000, serve.port());
        assertEquals(8, serve.threads());
        assertEquals("ca.key", serve.ca().toString());
        assertEquals("CN=DPS CA", serve.issuer());

        KeyServer.Command issuer = KeyServer.parse(new String[] {
                "--port", "9000", "--threads", "1", "--ca", "ca.key", "--issuer", "CN=Homework"});
        assertEquals("CN=Homework", issuer.issuer());
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> KeyServer.parse(new String[] {"--generate-ca", "ca.key", "--port", "9000"}));
        assertThrows(IllegalArgumentException.class,
                () -> KeyServer.parse(new String[] {"--port", "0", "--threads", "1", "--ca", "ca.key"}));
        assertThrows(IllegalArgumentException.class,
                () -> KeyServer.parse(new String[] {"--port", "9000", "--threads", "0", "--ca", "ca.key"}));
        assertThrows(IllegalArgumentException.class,
                () -> KeyServer.parse(new String[] {"--ca", "ca.key", "--threads", "1"}));
        assertThrows(IllegalArgumentException.class,
                () -> KeyServer.parse(new String[] {"--nope"}));
    }
}
