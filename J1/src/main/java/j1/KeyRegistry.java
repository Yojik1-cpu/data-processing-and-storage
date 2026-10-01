package j1;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class KeyRegistry {
    enum Kind { START, WAIT, READY }

    record Attach(Kind kind, byte[] response) {}

    private static final class Entry {
        private final List<Object> waiters = new ArrayList<>();
        private byte[] response;
    }

    private final ConcurrentHashMap<String, Entry> names = new ConcurrentHashMap<>();

    static boolean allowed(String name) {
        if (name == null || name.isEmpty() || name.length() > 256) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7E || "\\/:*?\"<>|".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    Attach attach(String name, Object waiter) {
        while (true) {
            Entry created = new Entry();
            Entry previous = names.putIfAbsent(name, created);
            Entry entry = previous == null ? created : previous;
            synchronized (entry) {
                if (names.get(name) != entry) {
                    continue;
                }
                if (entry.response != null) {
                    return new Attach(Kind.READY, entry.response);
                }
                entry.waiters.add(waiter);
                if (entry == created) {
                    return new Attach(Kind.START, null);
                }
                return new Attach(Kind.WAIT, null);
            }
        }
    }

    List<Object> complete(String name, byte[] response) {
        Entry entry = names.get(name);
        synchronized (entry) {
            if (names.get(name) != entry || entry.response != null) {
                throw new IllegalStateException(name);
            }
            entry.response = response;
            List<Object> waiters = List.copyOf(entry.waiters);
            entry.waiters.clear();
            return waiters;
        }
    }

    List<Object> fail(String name) {
        Entry entry = names.get(name);
        if (entry == null) {
            return List.of();
        }
        synchronized (entry) {
            if (names.get(name) != entry || entry.response != null) {
                return List.of();
            }
            names.remove(name, entry);
            return List.copyOf(entry.waiters);
        }
    }

    void detach(String name, Object waiter) {
        Entry entry = names.get(name);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            if (names.get(name) != entry) {
                return;
            }
            entry.waiters.remove(waiter);
        }
    }
}
