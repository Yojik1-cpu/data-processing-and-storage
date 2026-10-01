package j1;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class KeyClient {
    record Command(String name, String host, int port, int delay, boolean drop) {}

    public static void main(String[] args) {
        try {
            Command command = parse(args);
            if (!KeyRegistry.allowed(command.name())) {
                throw new IllegalArgumentException("bad name");
            }
            try (Socket socket = new Socket(command.host(), command.port())) {
                socket.getOutputStream().write(command.name().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().write(0);
                socket.getOutputStream().flush();
                if (command.delay() > 0) {
                    Thread.sleep(command.delay() * 1000L);
                }
                if (command.drop()) {
                    return;
                }
                byte[][] parts = readPair(socket.getInputStream());
                save(Path.of("").toAbsolutePath(), command.name(), parts[0], parts[1]);
            }
        } catch (Exception e) {
            System.err.println(e.getMessage() == null ? e.toString() : e.getMessage());
            System.exit(1);
        }
    }

    static Command parse(String[] args) {
        String name = null;
        String host = null;
        Integer port = null;
        int delay = 0;
        boolean drop = false;
        boolean delaySet = false;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--name" -> {
                    if (name != null) {
                        throw new IllegalArgumentException("repeated flag: --name");
                    }
                    name = value(args, i, flag);
                    i++;
                }
                case "--host" -> {
                    if (host != null) {
                        throw new IllegalArgumentException("repeated flag: --host");
                    }
                    host = value(args, i, flag);
                    i++;
                }
                case "--port" -> {
                    if (port != null) {
                        throw new IllegalArgumentException("repeated flag: --port");
                    }
                    port = integer(value(args, i, flag), 1, 65535, "port is outside 1..65535");
                    i++;
                }
                case "--delay" -> {
                    if (delaySet) {
                        throw new IllegalArgumentException("repeated flag: --delay");
                    }
                    delay = integer(value(args, i, flag), 0, Integer.MAX_VALUE, "delay is less than 0");
                    delaySet = true;
                    i++;
                }
                case "--drop" -> {
                    if (drop) {
                        throw new IllegalArgumentException("repeated flag: --drop");
                    }
                    drop = true;
                }
                default -> throw new IllegalArgumentException("unknown flag: " + flag);
            }
        }
        if (name == null) {
            throw new IllegalArgumentException("missing --name");
        }
        if (host == null) {
            throw new IllegalArgumentException("missing --host");
        }
        if (port == null) {
            throw new IllegalArgumentException("missing --port");
        }
        if (!KeyRegistry.allowed(name)) {
            throw new IllegalArgumentException("bad name");
        }
        return new Command(name, host, port, delay, drop);
    }

    static byte[][] readPair(InputStream in) throws IOException {
        return new byte[][] { readBlock(in), readBlock(in) };
    }

    private static byte[] readBlock(InputStream in) throws IOException {
        byte[] header = readFully(in, 4);
        int length = ByteBuffer.wrap(header).getInt();
        if (length <= 0 || length > 1_048_576) {
            throw new IOException("bad length");
        }
        return readFully(in, length);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int read = in.read(buf, off, length - off);
            if (read < 0) {
                throw new IOException("short response");
            }
            off += read;
        }
        return buf;
    }

    static void save(Path dir, String name, byte[] keyPem, byte[] certPem) throws IOException {
        Path key = dir.resolve(name + ".key");
        Path crt = dir.resolve(name + ".crt");
        Path keyTmp = dir.resolve(name + ".key.part");
        Path crtTmp = dir.resolve(name + ".crt.part");
        Path keyBak = dir.resolve(name + ".key.bak");
        Files.write(keyTmp, keyPem);
        Files.write(crtTmp, certPem);
        boolean hadKey = Files.isRegularFile(key);
        boolean movedKey = false;
        try {
            if (hadKey) {
                Files.move(key, keyBak, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(keyTmp, key, StandardCopyOption.REPLACE_EXISTING);
            movedKey = true;
            if (Files.isDirectory(crt)) {
                throw new IOException(crt.toString());
            }
            Files.move(crtTmp, crt, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(keyBak);
        } catch (IOException e) {
            Files.deleteIfExists(keyTmp);
            Files.deleteIfExists(crtTmp);
            if (movedKey) {
                Files.deleteIfExists(key);
            }
            if (hadKey && Files.exists(keyBak)) {
                Files.move(keyBak, key, StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }
    }

    private static String value(String[] args, int index, String flag) {
        if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
            throw new IllegalArgumentException("missing value: " + flag);
        }
        return args[index + 1];
    }

    private static int integer(String raw, int min, int max, String message) {
        try {
            int parsed = Integer.parseInt(raw);
            if (parsed < min || parsed > max) {
                throw new IllegalArgumentException(message);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(message);
        }
    }

    private KeyClient() {}
}
