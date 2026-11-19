package md.utm.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import md.utm.gateway.proxy.Header;
import md.utm.gateway.proxy.ProxyResponse;

/**
 * Response cache in Redis.
 *
 * Keys:
 *   gateway:gen:{warehouse}:{collection}                              → generation counter (INCR on write)
 *   gateway:resp:{warehouse}:{collection}:{gen}:{accept}:{path?query} → cached response, with a TTL
 *
 * The Accept header is part of the key, so the JSON and the XML version of the same
 * resource are cached separately.
 *
 * If Redis is unreachable the gateway keeps working without a cache. After a failure
 * the cache is skipped for a few seconds so requests do not each wait for a Redis timeout.
 */
public class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);
    private static final long RETRY_AFTER_MS = 5_000;
    private static final List<String> KEPT_HEADERS = List.of("Content-Type", "X-Instance-Id");

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private volatile long skipUntil;          // System.currentTimeMillis() until which Redis is skipped

    public RedisResponseCache(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public Optional<String> key(String warehouse, String collection, String accept, String pathAndQuery) {
        if (!isAvailable()) return Optional.empty();
        try {
            String gen = redis.opsForValue().get(genKey(warehouse, collection));
            return Optional.of("gateway:resp:" + warehouse + ":" + collection + ":" + (gen == null ? "0" : gen)
                    + ":" + accept + ":" + pathAndQuery);
        } catch (RuntimeException e) {
            failed("key", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<ProxyResponse> get(String key) {
        if (!isAvailable()) return Optional.empty();
        try {
            return Optional.ofNullable(redis.opsForValue().get(key)).map(RedisResponseCache::decode);
        } catch (RuntimeException e) {
            failed("get", e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, ProxyResponse response) {
        if (!isAvailable()) return;
        try {
            redis.opsForValue().set(key, encode(response), ttl);     // SET key value EX ttl — atomic
        } catch (RuntimeException e) {
            failed("put", e);
        }
    }

    @Override
    public void invalidate(String warehouse, String collection) {
        try {
            // INCR is atomic in Redis, so concurrent writes on several gateways never lose a bump
            redis.opsForValue().increment(genKey(warehouse, collection));
        } catch (RuntimeException e) {
            // the stale entries will still expire with their TTL
            failed("invalidate", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return System.currentTimeMillis() >= skipUntil;
    }

    private void failed(String op, RuntimeException e) {
        if (isAvailable()) {
            log.warn("Redis cache {} failed, caching is off for {} ms: {}", op, RETRY_AFTER_MS, e.toString());
        }
        skipUntil = System.currentTimeMillis() + RETRY_AFTER_MS;
    }

    private static String genKey(String warehouse, String collection) {
        return "gateway:gen:" + warehouse + ":" + collection;
    }

    // ---- value format: status \n Content-Type \n X-Instance-Id \n base64(body) ----

    static String encode(ProxyResponse r) {
        StringBuilder sb = new StringBuilder().append(r.status()).append('\n');
        for (String name : KEPT_HEADERS) {
            String v = r.header(name);
            sb.append(v == null ? "" : v.replace('\n', ' ')).append('\n');
        }
        return sb.append(Base64.getEncoder().encodeToString(r.body())).toString();
    }

    static ProxyResponse decode(String value) {
        String[] parts = value.split("\n", -1);
        List<Header> headers = new ArrayList<>();
        for (int i = 0; i < KEPT_HEADERS.size(); i++) {
            if (!parts[i + 1].isEmpty()) headers.add(new Header(KEPT_HEADERS.get(i), parts[i + 1]));
        }
        byte[] body = Base64.getDecoder().decode(parts[KEPT_HEADERS.size() + 1].getBytes(StandardCharsets.US_ASCII));
        return new ProxyResponse(Integer.parseInt(parts[0]), headers, body);
    }
}
