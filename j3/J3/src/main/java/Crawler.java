import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Crawler {
    static final Duration TIMEOUT = Duration.ofSeconds(20);

    record Page(String message, List<String> successors) {}

    record Token(String text, int next) {}

    public static void main(String[] args) throws Exception {
        long started = System.nanoTime();
        try {
            String host = args.length > 0 ? args[0] : "localhost";
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
            String origin = "http://" + host + ":" + port;
            List<String> messages = crawl(origin);
            if (messages.isEmpty()) {
                System.out.println("Start adress does not open");
                Files.writeString(Path.of("result.txt"), "", StandardCharsets.UTF_8);
                return;
            }
            System.out.println("===================================");
            Collections.sort(messages);
            String text = String.join("\n", messages) + "\n";
            System.out.print(text);
            Files.writeString(Path.of("result.txt"), text, StandardCharsets.UTF_8);
        } finally {
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            System.out.printf("Time of working: %.1f с%n", seconds);
        }
    }

    static List<String> crawl(String origin) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        List<String> messages = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inflight = new AtomicInteger(0);
        Object done = new Object();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            class Walk {
                void start(String path) {
                    String normalized = normalize(path);
                    if (!seen.add(normalized)) {
                        return;
                    }
                    inflight.incrementAndGet();
                    pool.submit(() -> {
                        try {
                            Page page = fetch(client, origin + normalized);
                            if (page != null) {
                                System.out.println("from " + normalized + " get " + page.message());
                                messages.add(page.message());
                                for (String next : page.successors()) {
                                    start(next);
                                }
                            }
                        } finally {
                            synchronized (done) {
                                if (inflight.decrementAndGet() == 0) {
                                    done.notifyAll();
                                }
                            }
                        }
                    });
                }
            }
            new Walk().start("/");
            synchronized (done) {
                while (inflight.get() > 0) {
                    done.wait();
                }
            }
        }
        return new ArrayList<>(messages);
    }

    static String normalize(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    static Page fetch(HttpClient client, String url) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                    continue;
                }
                Page page = parse(response.body());
                if (page != null) {
                    return page;
                }
            } catch (Exception ignored) {
                // следующая попытка
            }
        }
        return null;
    }

    static Page parse(String body) {
        int messageKey = body.indexOf("\"message\"");
        int successorsKey = body.indexOf("\"successors\"");
        if (messageKey < 0 || successorsKey < 0) {
            return null;
        }
        Token message = readString(body, afterValue(body, messageKey));
        List<String> successors = readArray(body, afterValue(body, successorsKey));
        if (message == null || successors == null) {
            return null;
        }
        return new Page(message.text(), successors);
    }

    static int afterValue(String body, int keyStart) {
        int colon = body.indexOf(':', keyStart);
        if (colon < 0) {
            return -1;
        }
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        return i;
    }

    static Token readString(String body, int i) {
        if (i < 0 || i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        int end = body.indexOf('"', i + 1);
        if (end < 0) {
            return null;
        }
        return new Token(body.substring(i + 1, end), end + 1);
    }

    static List<String> readArray(String body, int i) {
        if (i < 0 || i >= body.length() || body.charAt(i) != '[') {
            return null;
        }
        List<String> values = new ArrayList<>();
        int p = i + 1;
        while (p < body.length()) {
            while (p < body.length() && Character.isWhitespace(body.charAt(p))) {
                p++;
            }
            if (p < body.length() && body.charAt(p) == ']') {
                return values;
            }
            Token token = readString(body, p);
            if (token == null) {
                return null;
            }
            values.add(token.text());
            p = token.next();
            while (p < body.length() && Character.isWhitespace(body.charAt(p))) {
                p++;
            }
            if (p < body.length() && body.charAt(p) == ',') {
                p++;
                continue;
            }
            if (p < body.length() && body.charAt(p) == ']') {
                return values;
            }
            return null;
        }
        return null;
    }
}
