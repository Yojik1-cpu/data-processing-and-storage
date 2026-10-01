package j1;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class KeyServer {
    enum Mode { GENERATE, SERVE }

    record Command(Mode mode, Path generatePath, int port, int threads, Path ca, String issuer) {}

    public static void main(String[] args) throws InterruptedException {
        Command command;
        try {
            command = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        try {
            if (command.mode() == Mode.GENERATE) {
                Path path = command.generatePath().toAbsolutePath();
                if (Files.exists(path)) {
                    if (!confirmOverwrite(path)) {
                        System.err.println("left existing file: " + path);
                        System.exit(1);
                        return;
                    }
                    Files.delete(path);
                }
                SigningKey.generate(command.generatePath());
                return;
            }
            serve(command);
        } catch (Exception e) {
            System.err.println(e.getMessage() == null ? e.toString() : e.getMessage());
            System.exit(1);
        }
    }

    private static void serve(Command command) throws Exception {
        java.security.interfaces.RSAPrivateCrtKey ca;
        try {
            ca = SigningKey.load(command.ca());
        } catch (Exception e) {
            throw new IllegalStateException("cannot read key: " + command.ca());
        }
        try {
            new org.bouncycastle.asn1.x500.X500Name(command.issuer());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad issuer name");
        }
        ServerLoop loop = new ServerLoop(command.port(), command.threads(), new KeyRegistry(), ca, command.issuer());
        Thread io = new Thread(loop, "selector");
        io.setDaemon(false);
        io.start();
        io.join();
        if (loop.fatal != null) {
            String message = loop.fatal.getMessage();
            System.err.println(message == null ? "failed to open port" : "failed to open port: " + message);
            System.exit(1);
        }
    }

    static Command parse(String[] args) {
        boolean generate = false;
        boolean issuerSet = false;
        String generatePath = null;
        Integer port = null;
        Integer threads = null;
        String ca = null;
        String issuer = "CN=DPS CA";
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--generate-ca" -> {
                    if (generate) {
                        throw new IllegalArgumentException("repeated flag: --generate-ca");
                    }
                    generate = true;
                    generatePath = value(args, i, flag);
                    i++;
                }
                case "--port" -> {
                    if (port != null) {
                        throw new IllegalArgumentException("repeated flag: --port");
                    }
                    port = integer(value(args, i, flag), 1, 65535, "port is outside 1..65535");
                    i++;
                }
                case "--threads" -> {
                    if (threads != null) {
                        throw new IllegalArgumentException("repeated flag: --threads");
                    }
                    threads = integer(value(args, i, flag), 1, Integer.MAX_VALUE, "thread count is less than 1");
                    i++;
                }
                case "--ca" -> {
                    if (ca != null) {
                        throw new IllegalArgumentException("repeated flag: --ca");
                    }
                    ca = value(args, i, flag);
                    i++;
                }
                case "--issuer" -> {
                    if (issuerSet) {
                        throw new IllegalArgumentException("repeated flag: --issuer");
                    }
                    issuer = value(args, i, flag);
                    issuerSet = true;
                    i++;
                }
                default -> throw new IllegalArgumentException("unknown flag: " + flag);
            }
        }
        if (generate) {
            if (port != null || threads != null || ca != null || issuerSet) {
                throw new IllegalArgumentException("--generate-ca cannot be combined with server options");
            }
            return new Command(Mode.GENERATE, Path.of(generatePath), 0, 0, null, null);
        }
        if (port == null) {
            throw new IllegalArgumentException("missing --port");
        }
        if (threads == null) {
            throw new IllegalArgumentException("missing --threads");
        }
        if (ca == null) {
            throw new IllegalArgumentException("missing --ca");
        }
        return new Command(Mode.SERVE, null, port, threads, Path.of(ca), issuer);
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

    private static boolean confirmOverwrite(Path path) throws java.io.IOException {
        System.err.println(path.getFileName() + " already exists.");
        System.err.println("The current file will be destroyed.");
        System.err.print("Overwrite it? [y/N] ");
        System.err.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        return line != null && (line.equalsIgnoreCase("y") || line.equalsIgnoreCase("yes"));
    }

    private KeyServer() {}
}
