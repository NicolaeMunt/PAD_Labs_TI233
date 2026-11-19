package md.utm.gateway.backend;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.proxy.BackendClient;

/**
 * Active health checks: polls every backend's /actuator/health on a fixed delay.
 *
 * A backend goes DOWN as soon as a client request to it fails (passive check, in
 * ProxyService), or after {@code failuresBeforeDown} failed polls in a row here, so one
 * slow poll does not make it flap. It comes back UP after one successful poll. The API's
 * health endpoint also reports MongoDB and Redis, so an instance that lost its database
 * is taken out of rotation too.
 */
public class HealthChecker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HealthChecker.class);

    private final BackendRegistry registry;
    private final BackendClient client;
    private final GatewayProperties.Health cfg;
    private final Timeout probeTimeout;
    /** only touched by the single health-check thread */
    private final Map<Backend, Integer> failuresInARow = new HashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("health-check").daemon().factory());

    public HealthChecker(BackendRegistry registry, BackendClient client, GatewayProperties.Health cfg) {
        this.registry = registry;
        this.client = client;
        this.cfg = cfg;
        this.probeTimeout = Timeout.ofMilliseconds(cfg.timeout().toMillis());
    }

    public void start() {
        log.info("Health checks every {} for {}", cfg.interval(), registry.all());
        timer.scheduleWithFixedDelay(this::checkAll, 0, cfg.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    void checkAll() {
        for (Backend b : registry.all()) {
            String failure;
            try {
                int status = client.probe(b, probeTimeout);
                failure = status == 200 ? null : "health check returned HTTP " + status;
            } catch (Exception e) {
                failure = "health check failed: " + e;
            }

            if (failure == null) {
                failuresInARow.put(b, 0);
                registry.markUp(b);
            } else if (failuresInARow.merge(b, 1, Integer::sum) >= cfg.failuresBeforeDown()) {
                registry.markDown(b, failure);
            }
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }
}
