package md.utm.gateway.cache;

import java.util.Optional;

import md.utm.gateway.proxy.ProxyResponse;

/**
 * Cache of whole backend responses, shared by all gateway threads.
 *
 * Every warehouse has its own database, so the same path gives different answers in
 * different warehouses: the warehouse is part of every key and every generation counter.
 *
 * Invalidation uses a generation number per warehouse and resource collection (e.g.
 * warehouse-2 + /api/games): every key contains the current generation, and a write bumps
 * it. Old entries are then never looked up again and simply expire with their TTL.
 */
public interface ResponseCache {

    /**
     * Builds the cache key for a GET. Reads the collection's current generation, so it
     * must be called <b>before</b> the request is sent to a backend: a response that
     * raced with a write is then stored under the old generation and never served.
     *
     * @return the key, or empty if the cache is unavailable right now
     */
    Optional<String> key(String warehouse, String collection, String accept, String pathAndQuery);

    Optional<ProxyResponse> get(String key);

    void put(String key, ProxyResponse response);

    /** Makes every cached response of the collection in this warehouse stale. */
    void invalidate(String warehouse, String collection);

    boolean isAvailable();

    /** Does nothing: used when gateway.cache.enabled=false. */
    ResponseCache DISABLED = new ResponseCache() {
        public Optional<String> key(String w, String c, String a, String p) { return Optional.empty(); }
        public Optional<ProxyResponse> get(String key)                    { return Optional.empty(); }
        public void put(String key, ProxyResponse response)               { }
        public void invalidate(String warehouse, String collection)       { }
        public boolean isAvailable()                                      { return false; }
    };
}
