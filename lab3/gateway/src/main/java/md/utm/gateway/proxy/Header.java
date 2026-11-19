package md.utm.gateway.proxy;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public record Header(String name, String value) {

    /**
     * Hop-by-hop headers describe one TCP connection (client→gateway or gateway→API),
     * so a proxy must not pass them on. Content-Length and Host are set again by
     * whoever sends the next hop.
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade",
            "host", "content-length");

    public static boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    /** First value of a header, case-insensitive, or null. */
    public static String first(List<Header> headers, String name) {
        for (Header h : headers) {
            if (h.name().equalsIgnoreCase(name)) return h.value();
        }
        return null;
    }
}
