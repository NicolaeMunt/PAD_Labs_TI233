package md.utm.gateway.balancer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import md.utm.gateway.backend.Backend;

class BalancerTest {

    private final Backend a = new Backend(URI.create("http://a:8080"));
    private final Backend b = new Backend(URI.create("http://b:8080"));
    private final Backend c = new Backend(URI.create("http://c:8080"));
    private final List<Backend> all = List.of(a, b, c);

    @Test
    void roundRobinTakesBackendsInTurnAndWrapsAround() {
        RoundRobinBalancer rr = new RoundRobinBalancer();
        assertThat(List.of(rr.choose(all), rr.choose(all), rr.choose(all), rr.choose(all), rr.choose(all)))
                .containsExactly(a, b, c, a, b);
    }

    @Test
    void roundRobinIsFairUnderConcurrency() throws Exception {
        RoundRobinBalancer rr = new RoundRobinBalancer();
        Map<Backend, AtomicInteger> counts = new ConcurrentHashMap<>();
        int threads = 16, perThread = 3_000;
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        counts.computeIfAbsent(rr.choose(all), k -> new AtomicInteger()).incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
        }

        // 48 000 picks over 3 backends: every ticket is unique, so the split is exact
        assertThat(counts.get(a).get()).isEqualTo(16_000);
        assertThat(counts.get(b).get()).isEqualTo(16_000);
        assertThat(counts.get(c).get()).isEqualTo(16_000);
    }

    @Test
    void leastConnectionsPicksTheLeastBusyBackend() {
        LeastConnectionsBalancer lc = new LeastConnectionsBalancer();
        a.requestStarted(); a.requestStarted();
        b.requestStarted();
        // c has 0 requests in flight
        assertThat(lc.choose(all)).isEqualTo(c);

        c.requestStarted(); c.requestStarted();
        assertThat(lc.choose(all)).isEqualTo(b);
    }

    @Test
    void leastConnectionsSharesTiesRoundRobin() {
        LeastConnectionsBalancer lc = new LeastConnectionsBalancer();
        assertThat(List.of(lc.choose(all), lc.choose(all), lc.choose(all))).containsExactly(a, b, c);
    }
}
