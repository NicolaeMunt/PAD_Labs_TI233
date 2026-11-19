package md.utm.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code gateway.*} in application.properties.
 *
 * @param backends       base URLs of the API instances (DW nodes), e.g. http://api-1:8080
 * @param strategy       load-balancing algorithm
 * @param health         active health checks of the backends
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        List<URI> backends,
        @DefaultValue("round-robin") Strategy strategy,
        @DefaultValue Health health,
        @DefaultValue Pool pool,
        @DefaultValue Cache cache) {

    public enum Strategy { ROUND_ROBIN, LEAST_CONNECTIONS }

    /**
     * @param interval           how often every backend's /actuator/health is polled
     * @param timeout            a poll slower than this counts as failed
     * @param failuresBeforeDown consecutive failed polls before a backend is taken out
     *                           (a failed client request takes it out immediately)
     */
    public record Health(
            @DefaultValue("2s") Duration interval,
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("2") int failuresBeforeDown) { }

    /**
     * Pool of keep-alive connections from the gateway to the backends.
     *
     * @param maxTotal        connections to all backends together
     * @param maxPerBackend   connections to one backend
     * @param connectTimeout  how long to wait for a TCP connect
     * @param responseTimeout how long to wait for a backend's answer before failing over
     * @param idleTimeout     idle pooled connections are closed after this
     */
    public record Pool(
            @DefaultValue("200") int maxTotal,
            @DefaultValue("50") int maxPerBackend,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("10s") Duration responseTimeout,
            @DefaultValue("15s") Duration idleTimeout) { }

    /**
     * Response cache in Redis.
     *
     * @param enabled      turn the cache off completely
     * @param ttl          how long a cached response lives
     * @param paths        path prefixes whose GET responses are cached
     * @param maxBodyBytes bigger responses are not cached
     */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration ttl,
            @DefaultValue("/api/") List<String> paths,
            @DefaultValue("1048576") int maxBodyBytes) { }
}
