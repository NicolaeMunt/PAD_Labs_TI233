package md.utm.gateway.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The hand-off between request threads and the scaler: once the scaler has retired a
 * backend and seen it with no request in flight, no request may start on it any more,
 * because its container is about to be stopped.
 */
class BackendRetireTest {

    @RepeatedTest(20)
    void noRequestStartsAfterTheDrainCompleted() throws Exception {
        Backend b = new Backend(URI.create("http://api-warehouse-2-1:8080"), "warehouse-2", "c1", true);
        BackendRegistry registry = new BackendRegistry();
        registry.register(b);

        AtomicBoolean drained = new AtomicBoolean();
        AtomicInteger lateRequests = new AtomicInteger();
        AtomicInteger served = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 20_000; i++) {
                        if (b.tryAcquire()) {
                            if (drained.get()) lateRequests.incrementAndGet();
                            served.incrementAndGet();
                            b.requestAnswered();
                        }
                    }
                    return null;
                });
            }
            start.countDown();
            while (served.get() < 1_000) Thread.onSpinWait();    // let traffic build up first

            registry.remove(b);                                  // what WarehouseScaler does
            while (b.active() > 0) Thread.onSpinWait();
            drained.set(true);
        }

        assertThat(lateRequests.get()).isZero();
        assertThat(b.active()).isZero();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void statesFollowTheLifecycle() {
        BackendRegistry registry = new BackendRegistry();
        Backend b = new Backend(URI.create("http://api-warehouse-3-1:8080"), "warehouse-3", "c1", false);
        registry.register(b);
        assertThat(b.state()).isEqualTo(Backend.State.STARTING);
        assertThat(b.isHealthy()).isFalse();

        registry.markUp(b);
        assertThat(b.state()).isEqualTo(Backend.State.UP);
        registry.markDown(b, "test");
        assertThat(b.state()).isEqualTo(Backend.State.DOWN);
        registry.markUp(b);
        registry.remove(b);
        assertThat(b.state()).isEqualTo(Backend.State.DRAINING);
        assertThat(b.isHealthy()).isFalse();
        assertThat(b.tryAcquire()).isFalse();
    }

    @Test
    void duplicateNamesAreRejected() {
        BackendRegistry registry = new BackendRegistry();
        registry.register(new Backend(URI.create("http://api-1:8080")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.register(new Backend(URI.create("http://api-1:8080"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void warehouseNamesAreValidated() {
        assertThat(Warehouses.normalize(" Warehouse-2 ")).isEqualTo("warehouse-2");
        assertThat(Warehouses.normalize("-bad")).isNull();
        assertThat(Warehouses.normalize("bad-")).isNull();
        assertThat(Warehouses.normalize("a/b")).isNull();
        assertThat(Warehouses.normalize("x".repeat(33))).isNull();
        assertThat(Warehouses.normalize("w")).isEqualTo("w");
    }
}
