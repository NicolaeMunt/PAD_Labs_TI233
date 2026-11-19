package md.utm.gateway.proxy;

import java.util.List;

/**
 * A complete backend answer, buffered in memory so it can be cached and shared.
 *
 * @param status  HTTP status
 * @param headers end-to-end response headers
 * @param body    response body, empty array if none
 */
public record ProxyResponse(int status, List<Header> headers, byte[] body) {

    public String header(String name) {
        return Header.first(headers, name);
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
