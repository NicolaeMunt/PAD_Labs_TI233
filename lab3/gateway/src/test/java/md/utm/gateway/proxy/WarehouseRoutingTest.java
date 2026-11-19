package md.utm.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import md.utm.gateway.InMemoryResponseCache;
import md.utm.gateway.StubBackend;
import md.utm.gateway.backend.Backend;
import md.utm.gateway.backend.BackendRegistry;
import md.utm.gateway.balancer.RoundRobinBalancer;
import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.proxy.ProxyService.CacheStatus;
import md.utm.gateway.proxy.ProxyService.Result;

/**
 * warehouse-1 = api-1 + api-2 (default), warehouse-2 = api-3. Each warehouse has its own
 * database, so requests and cached answers must never cross from one to the other.
 */
class WarehouseRoutingTest {

    private StubBackend api1, api2, api3;
    private BackendRegistry registry;
    private BackendClient client;
    private ProxyService proxy;

    @BeforeEach
    void setUp() throws Exception {
        api1 = new StubBackend("api-1");
        api2 = new StubBackend("api-2");
        api3 = new StubBackend("api-3");
        registry = BackendRegistry.fromConfig(List.of(
                api1.uri().toString(),                       // bare URL → default warehouse
                "warehouse-1=" + api2.uri(),
                "warehouse-2=" + api3.uri()), "warehouse-1");
        client = new BackendClient(new GatewayProperties.Pool(
                50, 20, Duration.ofSeconds(1), Duration.ofMillis(500), Duration.ofSeconds(15)));
        proxy = new ProxyService(registry, new RoundRobinBalancer(), client, new InMemoryResponseCache(),
                new GatewayProperties.Cache(true, Duration.ofSeconds(30), List.of("/api/"), 1 << 20), "warehouse-1");
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        api1.close();
        api2.close();
        api3.close();
    }

    @Test
    void configGroupsBackendsByWarehouse() {
        assertThat(registry.warehouses()).containsExactly("warehouse-1", "warehouse-2");
        assertThat(registry.inWarehouse("warehouse-1")).extracting(Backend::name)
                .containsExactly(api1.uri().getHost() + ":" + api1.uri().getPort(),
                                 api2.uri().getHost() + ":" + api2.uri().getPort());
    }

    @Test
    void headerPicksTheWarehouseAndNoHeaderMeansTheDefault() {
        for (int i = 0; i < 4; i++) {
            Result r = proxy.handle(get("/whoami", "warehouse-2"));
            assertThat(r.response().header("X-Instance-Id")).isEqualTo("api-3");
            assertThat(r.warehouse()).isEqualTo("warehouse-2");
        }
        List<String> defaults = new ArrayList<>();
        for (int i = 0; i < 4; i++) defaults.add(proxy.handle(get("/whoami", null)).response().header("X-Instance-Id"));
        assertThat(defaults).containsExactly("api-1", "api-2", "api-1", "api-2");
    }

    @Test
    void headerIsCaseInsensitive() {
        assertThat(proxy.handle(get("/whoami", " Warehouse-2 ")).response().header("X-Instance-Id")).isEqualTo("api-3");
    }

    @Test
    void neverFailsOverToAnotherWarehouse() {
        api3.stop();
        assertThatThrownBy(() -> proxy.handle(get("/whoami", "warehouse-2")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(502));
        assertThat(api1.totalHits() + api2.totalHits()).isZero();
        // and once it is marked down, the warehouse answers 503 straight away
        assertThatThrownBy(() -> proxy.handle(get("/whoami", "warehouse-2")))
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(503));
    }

    @Test
    void unknownWarehouseIs404AndInvalidNameIs400() {
        assertThatThrownBy(() -> proxy.handle(get("/whoami", "warehouse-9")))
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(404))
                .hasMessageContaining("warehouse-1, warehouse-2");
        assertThatThrownBy(() -> proxy.handle(get("/whoami", "../etc")))
                .satisfies(e -> assertThat(((GatewayException) e).status()).isEqualTo(400));
    }

    @Test
    void cacheIsSeparatePerWarehouse() {
        assertThat(proxy.handle(get("/api/games/1", "warehouse-1")).cacheStatus()).isEqualTo(CacheStatus.MISS);
        Result w2 = proxy.handle(get("/api/games/1", "warehouse-2"));
        assertThat(w2.cacheStatus()).isEqualTo(CacheStatus.MISS);           // not warehouse-1's answer
        assertThat(w2.response().header("X-Instance-Id")).isEqualTo("api-3");

        Result w2Again = proxy.handle(get("/api/games/1", "warehouse-2"));
        assertThat(w2Again.cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(w2Again.response().header("X-Instance-Id")).isEqualTo("api-3");
    }

    @Test
    void writeInvalidatesOnlyItsOwnWarehouse() {
        proxy.handle(get("/api/games", "warehouse-1"));
        proxy.handle(get("/api/games", "warehouse-2"));

        proxy.handle(new ProxyRequest("POST", "/api/games", "/api/games",
                List.of(new Header("X-Warehouse", "warehouse-2"), new Header("Content-Type", "application/json")),
                "{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(proxy.handle(get("/api/games", "warehouse-1")).cacheStatus()).isEqualTo(CacheStatus.HIT);
        assertThat(proxy.handle(get("/api/games", "warehouse-2")).cacheStatus()).isEqualTo(CacheStatus.MISS);
    }

    /**
     * Scale down and up while 400 requests run: every request is answered, the removed
     * instance gets no request once it has drained, and the added one takes traffic.
     */
    @Test
    void scalingWhileTrafficRunsLosesNoRequestAndDrainedInstanceGetsNoMore() throws Exception {
        try (StubBackend api4 = new StubBackend("api-4"); StubBackend api5 = new StubBackend("api-5")) {
            api1.delay(2); api2.delay(2); api4.delay(2); api5.delay(2);
            Backend managed4 = new Backend(api4.uri(), "warehouse-1", "container-4", true);
            registry.register(managed4);

            List<Future<Result>> results = new ArrayList<>();
            int hitsAfterDrain;
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 200; i++) results.add(pool.submit(() -> proxy.handle(get("/whoami", null))));

                // scale down while those run: retire, then wait until its requests finished
                registry.remove(managed4);
                while (managed4.active() > 0) Thread.onSpinWait();
                hitsAfterDrain = api4.totalHits();

                // scale up
                registry.register(new Backend(api5.uri(), "warehouse-1", "container-5", true));
                for (int i = 0; i < 200; i++) results.add(pool.submit(() -> proxy.handle(get("/whoami", null))));
            }

            for (Future<Result> f : results) assertThat(f.get().response().status()).isEqualTo(200);
            assertThat(api4.totalHits()).isEqualTo(hitsAfterDrain);
            assertThat(api5.totalHits()).isPositive();
            assertThat(api1.totalHits() + api2.totalHits() + api4.totalHits() + api5.totalHits()).isEqualTo(400);
            assertThat(api3.totalHits()).isZero();                         // other warehouse untouched
        }
    }

    private static ProxyRequest get(String path, String warehouse) {
        List<Header> headers = new ArrayList<>(List.of(new Header("Accept", "application/json")));
        if (warehouse != null) headers.add(new Header("X-Warehouse", warehouse));
        return new ProxyRequest("GET", path, path, List.copyOf(headers), new byte[0]);
    }
}
