package j1;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyRegistryTest {
    @Test
    void firstAttachStartsAndSecondWaits() {
        KeyRegistry registry = new KeyRegistry();
        Object first = new Object();
        Object second = new Object();
        assertEquals(KeyRegistry.Kind.START, registry.attach("alice", first).kind());
        assertEquals(KeyRegistry.Kind.WAIT, registry.attach("alice", second).kind());
        byte[] response = new byte[] {1, 2, 3};
        List<Object> waiters = registry.complete("alice", response);
        assertEquals(List.of(first, second), waiters);
        KeyRegistry.Attach again = registry.attach("alice", new Object());
        assertEquals(KeyRegistry.Kind.READY, again.kind());
        assertArrayEquals(response, again.response());
    }

    @Test
    void detachDropsOnlyThatWaiter() {
        KeyRegistry registry = new KeyRegistry();
        Object first = new Object();
        Object second = new Object();
        registry.attach("alice", first);
        registry.attach("alice", second);
        registry.detach("alice", first);
        assertEquals(List.of(second), registry.complete("alice", new byte[] {9}));
    }

    @Test
    void failRemovesNameSoNextAttachStarts() {
        KeyRegistry registry = new KeyRegistry();
        Object waiter = new Object();
        registry.attach("alice", waiter);
        assertEquals(List.of(waiter), registry.fail("alice"));
        assertEquals(KeyRegistry.Kind.START, registry.attach("alice", new Object()).kind());
    }

    @Test
    void failDoesNotEraseReadyResponse() {
        KeyRegistry registry = new KeyRegistry();
        registry.attach("alice", new Object());
        byte[] response = new byte[] {4};
        registry.complete("alice", response);
        assertEquals(List.of(), registry.fail("alice"));
        assertArrayEquals(response, registry.attach("alice", new Object()).response());
    }

    @Test
    void onlyOneStartUnderContention() throws Exception {
        KeyRegistry registry = new KeyRegistry();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<KeyRegistry.Kind>> kinds = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                kinds.add(pool.submit(() -> {
                    go.await();
                    return registry.attach("n", new Object()).kind();
                }));
            }
            go.countDown();
            int starts = 0;
            for (Future<KeyRegistry.Kind> kind : kinds) {
                if (kind.get() == KeyRegistry.Kind.START) {
                    starts++;
                }
            }
            assertEquals(1, starts);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void allowedNames() {
        assertTrue(KeyRegistry.allowed("alice"));
        assertTrue(KeyRegistry.allowed("a,b"));
        assertFalse(KeyRegistry.allowed(""));
        assertFalse(KeyRegistry.allowed("a".repeat(257)));
        assertFalse(KeyRegistry.allowed("a/b"));
        assertFalse(KeyRegistry.allowed("a\\b"));
        assertFalse(KeyRegistry.allowed("a:b"));
        assertFalse(KeyRegistry.allowed("a*b"));
        assertFalse(KeyRegistry.allowed("a?b"));
        assertFalse(KeyRegistry.allowed("a\"b"));
        assertFalse(KeyRegistry.allowed("a<b"));
        assertFalse(KeyRegistry.allowed("a>b"));
        assertFalse(KeyRegistry.allowed("a|b"));
        assertFalse(KeyRegistry.allowed("a\nb"));
        assertFalse(KeyRegistry.allowed("a\u0001b"));
    }
}
