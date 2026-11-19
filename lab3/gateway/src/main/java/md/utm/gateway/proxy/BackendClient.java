package md.utm.gateway.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import md.utm.gateway.backend.Backend;
import md.utm.gateway.config.GatewayProperties;

/**
 * Sends requests to the API instances over a pool of persistent (keep-alive) connections.
 *
 * Opening a TCP connection per request costs a handshake and a new socket on both sides.
 * The pool keeps connections to every backend open and hands them out to request
 * threads, so a busy gateway reuses a few dozen sockets instead of opening thousands.
 * The pool manager is thread-safe: a connection is leased by one thread at a time.
 */
public class BackendClient implements Closeable {

    private final PoolingHttpClientConnectionManager pool;
    private final CloseableHttpClient http;

    public BackendClient(GatewayProperties.Pool cfg) {
        this.pool = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(cfg.maxTotal())
                .setMaxConnPerRoute(cfg.maxPerBackend())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout(cfg.connectTimeout()))
                        .setSocketTimeout(timeout(cfg.responseTimeout()))
                        // a connection idle for 1 s is checked before reuse, in case the API closed it
                        .setValidateAfterInactivity(TimeValue.ofSeconds(1))
                        .build())
                .build();

        this.http = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(timeout(cfg.connectTimeout()))   // wait for a free pooled connection
                        .setResponseTimeout(timeout(cfg.responseTimeout()))
                        .build())
                // close connections idle longer than the API's own keep-alive timeout
                .evictIdleConnections(TimeValue.of(cfg.idleTimeout()))
                .evictExpiredConnections()
                // the gateway decides about retries itself (see ProxyService)
                .disableAutomaticRetries()
                // a proxy passes redirects, cookies and compressed bodies through untouched
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableContentCompression()
                .build();
    }

    /** Forwards one request to {@code backend}. Throws IOException if no answer arrived. */
    public ProxyResponse send(Backend backend, ProxyRequest request) throws IOException {
        URI uri = URI.create(backend.baseUri() + request.pathAndQuery());
        HttpUriRequestBase out = new HttpUriRequestBase(request.method(), uri);
        for (Header h : request.headers()) {
            out.addHeader(h.name(), h.value());
        }
        if (request.body().length > 0) {
            // no content type here: the client's Content-Type header is already copied as-is
            out.setEntity(new ByteArrayEntity(request.body(), null));
        }

        return http.execute(out, response -> {
            List<Header> headers = new ArrayList<>();
            for (var h : response.getHeaders()) {
                if (!Header.isHopByHop(h.getName()) && !h.getName().equalsIgnoreCase("date")) {
                    headers.add(new Header(h.getName(), h.getValue()));
                }
            }
            HttpEntity entity = response.getEntity();
            byte[] body = entity == null ? new byte[0] : EntityUtils.toByteArray(entity);
            return new ProxyResponse(response.getCode(), headers, body);
        });
    }

    /** GET {base}/actuator/health with a short timeout. Returns the HTTP status. */
    public int probe(Backend backend, Timeout timeout) throws IOException {
        HttpGet get = new HttpGet(backend.baseUri() + "/actuator/health");
        get.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .build());
        return http.execute(get, response -> {
            EntityUtils.consume(response.getEntity());     // return the connection to the pool
            return response.getCode();
        });
    }

    /** Connections per backend: leased = in use now, available = idle and kept alive. */
    public Map<String, PoolStats> poolStatsByHost() {
        Map<String, PoolStats> stats = new HashMap<>();
        for (HttpRoute route : pool.getRoutes()) {
            var target = route.getTargetHost();
            stats.put(target.getHostName() + ":" + target.getPort(), pool.getStats(route));
        }
        return stats;
    }

    public PoolStats totalPoolStats() {
        return pool.getTotalStats();
    }

    @Override
    public void close() throws IOException {
        http.close();
    }

    private static Timeout timeout(java.time.Duration d) {
        return Timeout.ofMilliseconds(d.toMillis());
    }
}
