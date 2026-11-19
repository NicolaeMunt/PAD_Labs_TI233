package md.utm.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * A fake API instance on a random local port. Answers every request with
 * {"instance":"<name>","method":...,"path":...} and an X-Instance-Id header,
 * after an optional delay. Counts the requests it received per method.
 */
public class StubBackend implements AutoCloseable {

    private final String name;
    private final HttpServer server;
    private final ConcurrentHashMap<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private volatile long delayMs;
    private volatile int status = 200;

    public StubBackend(String name) throws IOException {
        this.name = name;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            boolean health = path.equals("/actuator/health");
            if (!health) hits.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            sleep(health ? 0 : delayMs);

            String accept = exchange.getRequestHeaders().getFirst("Accept");
            boolean xml = accept != null && accept.contains("xml");
            String body = xml
                    ? "<r><instance>" + name + "</instance><path>" + path + "</path></r>"
                    : "{\"instance\":\"" + name + "\",\"method\":\"" + method + "\",\"path\":\"" + path
                      + "\",\"bodyLength\":" + requestBody.length
                      + ",\"forwardedFor\":\"" + exchange.getRequestHeaders().getFirst("X-Forwarded-For") + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", xml ? "application/xml" : "application/json");
            exchange.getResponseHeaders().add("X-Instance-Id", name);
            int code = health ? 200 : (method.equals("POST") && status == 200 ? 201 : status);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public String name()                { return name; }
    public void delay(long ms)          { this.delayMs = ms; }
    public void status(int code)        { this.status = code; }
    public int hits(String method)      { var c = hits.get(method); return c == null ? 0 : c.get(); }
    public int totalHits()              { return hits.values().stream().mapToInt(AtomicInteger::get).sum(); }

    public void stop() {
        server.stop(0);
    }

    @Override
    public void close() {
        stop();
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
