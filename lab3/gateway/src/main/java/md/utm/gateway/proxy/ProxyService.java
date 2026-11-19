package md.utm.gateway.proxy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;

import md.utm.gateway.backend.Backend;
import md.utm.gateway.backend.BackendRegistry;
import md.utm.gateway.backend.Warehouses;
import md.utm.gateway.balancer.LoadBalancer;
import md.utm.gateway.cache.ResponseCache;
import md.utm.gateway.config.GatewayProperties;

/**
 * The gateway's request pipeline:
 *
 * <ol>
 *   <li><b>Cache</b> – a GET under a cached path is answered from Redis when possible.</li>
 *   <li><b>Coalescing</b> – concurrent misses for the same key wait for one backend call.</li>
 *   <li><b>Load balancing</b> – otherwise a healthy backend is chosen by the balancer.</li>
 *   <li><b>Failover</b> – if that backend gives no answer, the request goes to the next one,
 *       when it is safe to repeat it.</li>
 *   <li><b>Invalidation</b> – a write under a cached path makes its collection's cache stale.</li>
 * </ol>
 *
 * Every step happens inside one <b>warehouse</b>, picked by the X-Warehouse header (or the
 * default one). Each warehouse has its own database, so a request is never failed over to
 * an instance of another warehouse: that instance would answer from different data.
 *
 * Stateless apart from thread-safe collaborators, so one instance serves all request threads.
 */
public class ProxyService {

    /** What the gateway did with the request; sent to the client as X-Cache. */
    public enum CacheStatus { HIT, MISS, COALESCED, BYPASS }

    public record Result(ProxyResponse response, CacheStatus cacheStatus, String warehouse) { }

    private final BackendRegistry registry;
    private final LoadBalancer balancer;
    private final BackendClient client;
    private final ResponseCache cache;
    private final GatewayProperties.Cache cacheCfg;
    private final String defaultWarehouse;
    private final GatewayStats stats = new GatewayStats();

    /** cache key → the backend call that is already loading it */
    private final ConcurrentHashMap<String, CompletableFuture<ProxyResponse>> inFlight = new ConcurrentHashMap<>();

    public ProxyService(BackendRegistry registry, LoadBalancer balancer, BackendClient client,
                        ResponseCache cache, GatewayProperties.Cache cacheCfg, String defaultWarehouse) {
        this.registry = registry;
        this.balancer = balancer;
        this.client = client;
        this.cache = cache;
        this.cacheCfg = cacheCfg;
        this.defaultWarehouse = Warehouses.requireValid(defaultWarehouse);
    }

    public GatewayStats stats() {
        return stats;
    }

    public Result handle(ProxyRequest req) {
        stats.requests.increment();
        String warehouse = warehouseOf(req);
        String collection = cachedCollection(req.path());

        if (collection != null && req.method().equals("GET") && req.header("Authorization") == null) {
            Optional<String> key = cache.key(warehouse, collection,
                    normalizeAccept(req.header("Accept")), req.pathAndQuery());
            if (key.isPresent()) return cachedGet(key.get(), req, warehouse);
        }

        try {
            return new Result(forward(req, warehouse), CacheStatus.BYPASS, warehouse);
        } finally {
            // Any write under a cached path invalidates, even a failed or timed-out one:
            // the API may have applied it before the answer got lost.
            if (collection != null && !req.isRead()) {
                cache.invalidate(warehouse, collection);
                stats.invalidations.increment();
            }
        }
    }

    /** The X-Warehouse header, or the default warehouse when there is none. */
    String warehouseOf(ProxyRequest req) {
        String raw = req.header(Warehouses.HEADER);
        if (raw == null || raw.isBlank()) return defaultWarehouse;
        String warehouse = Warehouses.normalize(raw);
        if (warehouse == null) {
            stats.errors.increment();
            throw new GatewayException(400, "Invalid " + Warehouses.HEADER + " header '" + raw + "'");
        }
        return warehouse;
    }

    private Result cachedGet(String key, ProxyRequest req, String warehouse) {
        Optional<ProxyResponse> hit = cache.get(key);
        if (hit.isPresent()) {
            stats.cacheHits.increment();
            return new Result(hit.get(), CacheStatus.HIT, warehouse);
        }

        // Only the first thread to miss calls a backend; the others wait for its answer.
        CompletableFuture<ProxyResponse> mine = new CompletableFuture<>();
        CompletableFuture<ProxyResponse> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) {
            stats.coalesced.increment();
            return new Result(await(leader), CacheStatus.COALESCED, warehouse);
        }

        try {
            ProxyResponse resp = forward(req, warehouse);
            if (resp.status() == 200 && resp.body().length <= cacheCfg.maxBodyBytes()) {
                cache.put(key, resp);   // before removing from inFlight, so late threads find it cached
            }
            stats.cacheMisses.increment();
            mine.complete(resp);
            return new Result(resp, CacheStatus.MISS, warehouse);
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private static ProxyResponse await(CompletableFuture<ProxyResponse> leader) {
        try {
            return leader.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    /**
     * Sends the request to a healthy backend of the warehouse, failing over to the warehouse's
     * other backends when that is safe.
     */
    ProxyResponse forward(ProxyRequest req, String warehouse) {
        List<Backend> members = registry.inWarehouse(warehouse);
        if (members.isEmpty()) {
            stats.errors.increment();
            throw new GatewayException(404, "Unknown warehouse '" + warehouse + "' (known: "
                    + String.join(", ", registry.warehouses()) + ")");
        }
        List<Backend> candidates = new ArrayList<>(members.stream().filter(Backend::isHealthy).toList());
        if (candidates.isEmpty()) {
            stats.errors.increment();
            throw new GatewayException(503, "No healthy API instance in warehouse '" + warehouse + "'");
        }

        IOException lastError = null;
        while (!candidates.isEmpty()) {
            Backend backend = balancer.choose(candidates);
            if (!backend.tryAcquire()) {
                // removed by a scale-down after the list was read: it is shutting down
                candidates.remove(backend);
                continue;
            }
            try {
                ProxyResponse resp = client.send(backend, req);
                backend.requestAnswered();
                return resp;
            } catch (IOException e) {
                backend.requestFailed();
                registry.markDown(backend, e.toString());
                lastError = e;

                // A request that never reached the API can always go elsewhere. One that may have
                // reached it is only repeated if repeating it is harmless (GET, PUT, DELETE).
                if (!wasNotSent(e) && !req.isIdempotent()) {
                    stats.errors.increment();
                    throw new GatewayException(e instanceof SocketTimeoutException ? 504 : 502,
                            "API instance " + backend + " did not answer a " + req.method()
                            + "; it was not retried because it may already have been applied");
                }
                candidates.remove(backend);
                if (!candidates.isEmpty()) stats.failovers.increment();
            }
        }
        stats.errors.increment();
        if (lastError == null) {
            throw new GatewayException(503, "No healthy API instance in warehouse '" + warehouse + "'");
        }
        throw new GatewayException(lastError instanceof SocketTimeoutException ? 504 : 502,
                "No API instance of warehouse '" + warehouse + "' answered (last error: " + lastError + ")");
    }

    /** True when the failure happened before any byte of the request was sent. */
    static boolean wasNotSent(IOException e) {
        return e instanceof ConnectException                    // refused (includes HttpHostConnectException)
                || e instanceof ConnectTimeoutException         // TCP connect timed out
                || e instanceof ConnectionRequestTimeoutException // no free connection in the pool
                || e instanceof UnknownHostException            // container is gone from Docker DNS
                || e instanceof NoRouteToHostException;
    }

    /** "/api/games/42" → "/api/games" when /api/ is a cached prefix, otherwise null. */
    String cachedCollection(String path) {
        if (!cacheCfg.enabled()) return null;
        for (String prefix : cacheCfg.paths()) {
            if (path.startsWith(prefix)) {
                String[] seg = path.split("/");      // ["", "api", "games", "42"]
                return seg.length >= 3 ? "/" + seg[1] + "/" + seg[2] : path;
            }
        }
        return null;
    }

    /** JSON and XML answers are cached apart; equivalent spellings of Accept share a key. */
    static String normalizeAccept(String accept) {
        if (accept == null || accept.isBlank()) return "*/*";
        return accept.toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
