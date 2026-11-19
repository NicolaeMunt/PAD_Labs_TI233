package md.utm.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import md.utm.gateway.InMemoryResponseCache;
import md.utm.gateway.StubBackend;
import md.utm.gateway.backend.BackendRegistry;
import md.utm.gateway.balancer.RoundRobinBalancer;
import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.proxy.ProxyService.CacheStatus;
import md.utm.gateway.proxy.ProxyService.Result;

/** The whole pipeline against three fake API instances over real HTTP. */
class ProxyServiceTest {

    private StubBackend api1, api2, api3;
    private BackendRegistry registry;
    private BackendClient client;
    private InMemoryResponseCache cache;
    private ProxyService proxy;

    @BeforeEach
    void setUp() throws Exception {
        api1 = new StubBackend("api-1");
        api2 = new StubBackend("api-2");
        api3 = new StubBackend("api-3");
        registry = BackendRegistry.of(List.of(api1.uri(), api2.uri(), api3.uri()));
        client = new BackendClient(new GatewayProperties.Pool(
                50, 20, Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(15)));
        cache = new InMemoryResponseCache();
        proxy = new ProxyService(registry, new RoundRobinBalancer(), client, cache,
                new GatewayProperties.Cache(true, Duration.ofSeconds(30), List.of("/api/"), 1 << 20), "warehouse-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        api1.close();
        api2.close();
        api3.close();
    }

    // ---------- load balancing ----------

    @Test
    void roundRobinSpreadsRequestsEvenly() {
        List<String> instances = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            instances.add(proxy.handle(get("/whoami", "application/json")).response().header("X-Instance-Id"));
        }
        assertThat(instances).containsExactly("api-1", "api-2", "api-3", "api-1", "api-2", "api-3");
    }

    @Test
    void stoppedBackendIsSkippedAndClientsSeeNoError() {
        api2.stop();
        for (int i = 0; i < 6; i++) {
            assertThat(proxy.handle(get("/whoami", "application/json")).response().status()).isEqualTo(200);
        }
        assertThat(registry.all().get(1).isHealthy()).isFalse();
        assertThat(api1.totalHits() + api3.totalHits()).isEqualTo(6);
        assertThat(proxy.stats().failovers()).isEqualTo(1);   // only the first request hit the dead one
    }

    @Test
    void noHealthyBackendGives503() {
        registry.all().forEach(b -> registry.markDown(b, "test"));
        assertThatThrownBy(() -> proxy.handle(get("/whoami", "application/json")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(503));
    }

    // ---------- failover rules ----------

    @Test
    void getIsRetriedOnAnotherBackendAfterTimeout() {
        api1.delay(2_000);                                       // longer than the 500 ms response timeout
        Result r = proxy.handle(get("/whoami", "application/json"));
        assertThat(r.response().status()).isEqualTo(200);
        assertThat(r.response().header("X-Instance-Id")).isIn("api-2", "api-3");
        assertThat(registry.all().get(0).isHealthy()).isFalse();
    }

    @Test
    void postIsNotRetriedAfterTimeoutBecauseItMayHaveBeenApplied() {
        api1.delay(2_000);
        assertThatThrownBy(() -> proxy.handle(post("/api/games", "{\"title\":\"Doom\"}")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(504));
        assertThat(api1.hits("POST")).isEqualTo(1);
        assertThat(api2.hits("POST") + api3.hits("POST")).isZero();
    }

    @Test
    void postIsRetriedWhenTheConnectionWasRefused() {
        api1.stop();                                             // nothing was sent, so retrying is safe
        Result r = proxy.handle(post("/api/games", "{\"title\":\"Doom\"}"));
        assertThat(r.response().status()).isEqualTo(201);
        assertThat(api2.hits("POST") + api3.hits("POST")).isEqualTo(1);   // created exactly once
    }

    @Test
    void requestBodyAndForwardedForReachTheBackend() {
        String posted = text(proxy.handle(post("/api/games", "{\"title\":\"Doom\"}")).response());
        assertThat(posted).contains("\"method\":\"POST\"").contains("\"bodyLength\":16");
        String json = text(proxy.handle(get("/whoami", "application/json")).response());
        assertThat(json).contains("\"forwardedFor\":\"10.0.0.7\"");
    }

    // ---------- cache ----------

    @Test
    void secondGetIsServedFromCache() {
        Result first = proxy.handle(get("/api/games/1", "application/json"));
        Result second = proxy.handle(get("/api/games/1", "application/json"));

        assertThat(first.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(second.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(text(second.response())).isEqualTo(text(first.response()));
        assertThat(api1.totalHits() + api2.totalHits() + api3.totalHits()).isEqualTo(1);
    }

    @Test
    void jsonAndXmlAreCachedSeparately() {
        Result json = proxy.handle(get("/api/games/1", "application/json"));
        Result xml = proxy.handle(get("/api/games/1", "application/xml"));
        Result xmlAgain = proxy.handle(get("/api/games/1", "application/xml"));

        assertThat(json.response().header("Content-Type")).isEqualTo("application/json");
        assertThat(xml.cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(xmlAgain.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(xmlAgain.response().header("Content-Type")).isEqualTo("application/xml");
        assertThat(text(xmlAgain.response())).startsWith("<r>");
    }

    @Test
    void writeInvalidatesTheCollection() {
        proxy.handle(get("/api/games", "application/json"));
        proxy.handle(get("/api/games/1", "application/json"));
        proxy.handle(post("/api/games", "{}"));

        assertThat(proxy.handle(get("/api/games", "application/json")).cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(proxy.handle(get("/api/games/1", "application/json")).cacheStatus()).isEqualTo(CacheStatus.MISS);
    }

    @Test
    void errorsAndUncachedPathsAreNotCached() {
        api1.status(404); api2.status(404); api3.status(404);
        proxy.handle(get("/api/games/missing", "application/json"));
        assertThat(proxy.handle(get("/api/games/missing", "application/json")).cacheStatus()).isEqualTo(CacheStatus.MISS);
        assertThat(proxy.handle(get("/whoami", "application/json")).cacheStatus()).isEqualTo(CacheStatus.BYPASS);
    }

    @Test
    void concurrentMissesForTheSameKeyCallTheBackendOnce() throws Exception {
        api1.delay(300); api2.delay(300); api3.delay(300);
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> results = new ArrayList<>();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return proxy.handle(get("/api/games/7", "application/json"));
                }));
            }
            start.countDown();
        }

        long misses = 0;
        for (Future<Result> f : results) {
            Result r = f.get();
            assertThat(r.response().status()).isEqualTo(200);
            if (r.cacheStatus() == CacheStatus.MISS) misses++;
        }
        assertThat(misses).isEqualTo(1);
        assertThat(api1.totalHits() + api2.totalHits() + api3.totalHits()).isEqualTo(1);
    }

    @Test
    void manyParallelRequestsAreAllAnsweredAndCounted() throws Exception {
        int requests = 300;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> proxy.handle(get("/whoami", "application/json"))));
            }
            for (Future<Result> f : results) assertThat(f.get().response().status()).isEqualTo(200);
        }
        assertThat(api1.totalHits()).isEqualTo(100);
        assertThat(api2.totalHits()).isEqualTo(100);
        assertThat(api3.totalHits()).isEqualTo(100);
        registry.all().forEach(b -> assertThat(b.active()).isZero());
        // keep-alive: 300 requests went over far fewer TCP connections
        assertThat(client.totalPoolStats().getAvailable()).isLessThanOrEqualTo(60);
    }

    // ---------- helpers ----------

    private static ProxyRequest get(String path, String accept) {
        return new ProxyRequest("GET", path, path,
                List.of(new Header("Accept", accept), new Header("X-Forwarded-For", "10.0.0.7")), new byte[0]);
    }

    private static ProxyRequest post(String path, String json) {
        return new ProxyRequest("POST", path, path,
                List.of(new Header("Content-Type", "application/json"), new Header("Accept", "application/json")),
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(ProxyResponse r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }
}
