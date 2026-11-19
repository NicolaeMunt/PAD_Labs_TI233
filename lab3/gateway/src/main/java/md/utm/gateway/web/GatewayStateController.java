package md.utm.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.core5.pool.PoolStats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import md.utm.gateway.backend.BackendRegistry;
import md.utm.gateway.balancer.LoadBalancer;
import md.utm.gateway.cache.ResponseCache;
import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.proxy.BackendClient;
import md.utm.gateway.proxy.ProxyService;

/** GET /gateway/state – what the gateway sees right now (for the demo and the UI). */
@RestController
public class GatewayStateController {

    private final BackendRegistry registry;
    private final LoadBalancer balancer;
    private final BackendClient client;
    private final ResponseCache cache;
    private final ProxyService proxy;
    private final GatewayProperties props;

    public GatewayStateController(BackendRegistry registry, LoadBalancer balancer, BackendClient client,
                                  ResponseCache cache, ProxyService proxy, GatewayProperties props) {
        this.registry = registry;
        this.balancer = balancer;
        this.client = client;
        this.cache = cache;
        this.proxy = proxy;
        this.props = props;
    }

    @GetMapping("/gateway/state")
    public Map<String, Object> state() {
        Map<String, PoolStats> pools = client.poolStatsByHost();

        List<Map<String, Object>> backends = registry.all().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.name());
            m.put("url", b.baseUri().toString());
            m.put("healthy", b.isHealthy());
            m.put("lastError", b.lastError());
            m.put("activeRequests", b.active());
            m.put("served", b.served());
            m.put("failed", b.failed());
            m.put("connections", pool(pools.get(b.name())));
            return m;
        }).toList();

        Map<String, Object> cacheInfo = new LinkedHashMap<>();
        cacheInfo.put("enabled", props.cache().enabled());
        cacheInfo.put("redisAvailable", cache.isAvailable());
        cacheInfo.put("ttl", props.cache().ttl().toString());
        cacheInfo.put("paths", props.cache().paths());

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("strategy", balancer.name());
        state.put("backends", backends);
        state.put("connectionPool", pool(client.totalPoolStats()));
        state.put("cache", cacheInfo);
        state.put("stats", proxy.stats().snapshot());
        return state;
    }

    private static Map<String, Integer> pool(PoolStats s) {
        if (s == null) return Map.of("leased", 0, "available", 0, "max", 0);
        return Map.of("leased", s.getLeased(), "available", s.getAvailable(), "max", s.getMax());
    }
}
