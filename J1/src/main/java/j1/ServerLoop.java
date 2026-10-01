package j1;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class ServerLoop implements Runnable {
    private final int port;
    private final int threads;
    private final KeyRegistry registry;
    private final PrivateKey ca;
    private final String issuer;
    private final LinkedBlockingQueue<String> generate = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Outbound> send = new ConcurrentLinkedQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private Selector selector;
    volatile Throwable fatal;

    ServerLoop(int port, int threads, KeyRegistry registry, PrivateKey ca, String issuer) {
        this.port = port;
        this.threads = threads;
        this.registry = registry;
        this.ca = ca;
        this.issuer = issuer;
    }

    static final class Outbound {
        final Conn conn;
        final byte[] bytes;

        Outbound(Conn conn, byte[] bytes) {
            this.conn = conn;
            this.bytes = bytes;
        }
    }

    static final class Conn {
        final SocketChannel channel;
        final ByteArrayOutputStream read = new ByteArrayOutputStream();
        ByteBuffer write;
        volatile boolean closed;
        String name;

        Conn(SocketChannel channel) {
            this.channel = channel;
        }
    }

    @Override
    public void run() {
        try {
            loop();
        } catch (Throwable thrown) {
            fatal = thrown;
        } finally {
            for (Thread worker : workers) {
                worker.interrupt();
            }
        }
    }

    private void loop() throws Exception {
        selector = Selector.open();
        ServerSocketChannel server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("0.0.0.0", port), 128);
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(this::generateLoop, "gen-" + i);
            worker.setDaemon(false);
            workers.add(worker);
            worker.start();
        }
        while (!Thread.currentThread().isInterrupted()) {
            drain();
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) {
                    continue;
                }
                try {
                    if (key.isAcceptable()) {
                        accept(server);
                    } else if (key.isReadable()) {
                        read((Conn) key.attachment());
                    } else if (key.isWritable()) {
                        write((Conn) key.attachment());
                    }
                } catch (IOException e) {
                    if (key.attachment() instanceof Conn conn) {
                        System.err.println("connection closed");
                        drop(conn);
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    private void generateLoop() {
        KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(8192);
        } catch (Exception e) {
            System.err.println("generation failed: " + e.getMessage());
            return;
        }
        while (!Thread.currentThread().isInterrupted()) {
            String name;
            try {
                name = generate.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            byte[] response;
            try {
                response = KeyIssuer.issue(name, generator, ca, issuer);
            } catch (Exception e) {
                System.err.println("generation failed for " + name + ": " + e.getMessage());
                for (Object waiter : registry.fail(name)) {
                    send.add(new Outbound((Conn) waiter, null));
                }
                selector.wakeup();
                continue;
            }
            for (Object waiter : registry.complete(name, response)) {
                Conn conn = (Conn) waiter;
                if (!conn.closed) {
                    send.add(new Outbound(conn, response));
                }
            }
            selector.wakeup();
        }
    }

    private void accept(ServerSocketChannel server) throws IOException {
        SocketChannel channel = server.accept();
        if (channel == null) {
            return;
        }
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_READ, new Conn(channel));
    }

    private void read(Conn conn) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(257);
        int n = conn.channel.read(buf);
        if (n < 0) {
            drop(conn);
            return;
        }
        if (conn.name != null) {
            return;
        }
        buf.flip();
        while (buf.hasRemaining()) {
            int b = buf.get() & 0xFF;
            if (b == 0) {
                String name = conn.read.toString(StandardCharsets.US_ASCII);
                if (!KeyRegistry.allowed(name)) {
                    System.err.println("bad name");
                    drop(conn);
                    return;
                }
                conn.name = name;
                KeyRegistry.Attach attach = registry.attach(name, conn);
                if (attach.kind() == KeyRegistry.Kind.START) {
                    generate.add(name);
                } else if (attach.kind() == KeyRegistry.Kind.READY) {
                    send.add(new Outbound(conn, attach.response()));
                }
                return;
            }
            if (b < 0x20 || b > 0x7E || "\\/:*?\"<>|".indexOf(b) >= 0) {
                System.err.println("bad name");
                drop(conn);
                return;
            }
            if (conn.read.size() >= 256) {
                System.err.println("name is longer than 256");
                drop(conn);
                return;
            }
            conn.read.write(b);
        }
    }

    private void drain() {
        Outbound item;
        while ((item = send.poll()) != null) {
            Conn conn = item.conn;
            if (conn.closed || !conn.channel.isOpen()) {
                continue;
            }
            if (item.bytes == null) {
                drop(conn);
                continue;
            }
            if (conn.write != null) {
                continue;
            }
            conn.write = ByteBuffer.wrap(item.bytes);
            SelectionKey key = conn.channel.keyFor(selector);
            if (key != null && key.isValid()) {
                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            }
        }
    }

    private void write(Conn conn) throws IOException {
        if (conn.write == null) {
            return;
        }
        conn.channel.write(conn.write);
        if (!conn.write.hasRemaining()) {
            drop(conn);
        }
    }

    private void drop(Conn conn) {
        if (conn.closed) {
            return;
        }
        conn.closed = true;
        if (conn.name != null) {
            registry.detach(conn.name, conn);
        }
        send.removeIf(item -> item.conn == conn);
        try {
            conn.channel.close();
        } catch (IOException ignored) {
            // channel is already closed
        }
    }
}
