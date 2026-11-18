import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlerTest {

    @Test
    void parseReadsMessageAndSuccessorsWithoutSlash() {
        Crawler.Page page = Crawler.parse("{\"message\":\"hi\",\"successors\":[\"578\",\"277\"]}");

        assertEquals("hi", page.message());
        assertEquals(List.of("578", "277"), page.successors());
    }

    @Test
    void parseReturnsNullWhenFieldsAreMissing() {
        assertNull(Crawler.parse("{\"message\":\"hi\"}"));
        assertNull(Crawler.parse(""));
    }

    @Test
    void normalizeAddsSlashAndKeepsExistingOne() {
        assertEquals("/578", Crawler.normalize("578"));
        assertEquals("/578", Crawler.normalize("/578"));
        assertEquals("/", Crawler.normalize("/"));
    }

    @Test
    void fetchReturnsNullAfterThreeRefusals() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Crawler.TIMEOUT).build();

        assertNull(Crawler.fetch(client, "http://127.0.0.1:9/"));
    }

    @Test
    void fetchRetriesUntilTheThirdResponseWorks() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (hits.incrementAndGet() < 3) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            respond(exchange, 200, "{\"message\":\"ok\",\"successors\":[]}");
        });
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Crawler.TIMEOUT).build();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            Crawler.Page page = Crawler.fetch(client, url);

            assertEquals("ok", page.message());
            assertEquals(3, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crawlKeepsDuplicateTextAndSkipsADeadPage() throws Exception {
        HttpServer server = server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/gone".equals(path)) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            String body = switch (path) {
                case "/" -> "{\"message\":\"b\",\"successors\":[\"a\",\"a\",\"gone\",\"b\"]}";
                case "/a" -> "{\"message\":\"a\",\"successors\":[\"/\"]}";
                case "/b" -> "{\"message\":\"b\",\"successors\":[]}";
                default -> null;
            };
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            respond(exchange, 200, body);
        });
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            List<String> messages = Crawler.crawl(origin);
            Collections.sort(messages);

            assertEquals(List.of("a", "b", "b"), messages);
            String log = captured.toString(StandardCharsets.UTF_8);
            assertTrue(log.contains("from / get b"));
            assertTrue(log.contains("from /a get a"));
            assertTrue(log.contains("from /b get b"));
            assertTrue(!log.contains("from /gone"));
        } finally {
            System.setOut(original);
            server.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
