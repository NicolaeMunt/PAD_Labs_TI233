package md.utm.gateway.proxy;

import java.util.List;
import java.util.Set;

/**
 * A client request as the gateway received it, already stripped of hop-by-hop headers.
 *
 * @param method       GET, POST, ...
 * @param path         raw (still URL-encoded) path, e.g. /api/games/42
 * @param pathAndQuery path plus "?query" if there is one
 * @param headers      end-to-end headers to forward
 * @param body         request body, empty array if none
 */
public record ProxyRequest(String method, String path, String pathAndQuery,
                           List<Header> headers, byte[] body) {

    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");

    /** Same result when repeated, so it may be re-sent to another backend after a failure. */
    public boolean isIdempotent() {
        return IDEMPOTENT.contains(method);
    }

    public boolean isRead() {
        return method.equals("GET") || method.equals("HEAD");
    }

    public String header(String name) {
        return Header.first(headers, name);
    }
}
