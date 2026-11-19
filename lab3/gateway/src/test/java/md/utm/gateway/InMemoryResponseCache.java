package md.utm.gateway;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import md.utm.gateway.cache.ResponseCache;
import md.utm.gateway.proxy.ProxyResponse;

/** Same key and generation scheme as RedisResponseCache, kept in memory (no TTL). */
public class InMemoryResponseCache implements ResponseCache {

    private final ConcurrentHashMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProxyResponse> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<String> key(String collection, String accept, String pathAndQuery) {
        long gen = generations.computeIfAbsent(collection, c -> new AtomicLong()).get();
        return Optional.of(collection + ":" + gen + ":" + accept + ":" + pathAndQuery);
    }

    @Override
    public Optional<ProxyResponse> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public void put(String key, ProxyResponse response) {
        entries.put(key, response);
    }

    @Override
    public void invalidate(String collection) {
        generations.computeIfAbsent(collection, c -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    public int size() {
        return entries.size();
    }
}
