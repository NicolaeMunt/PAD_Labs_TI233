package md.utm.gateway.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import md.utm.gateway.proxy.GatewayException;
import md.utm.gateway.proxy.Header;
import md.utm.gateway.proxy.ProxyRequest;
import md.utm.gateway.proxy.ProxyResponse;
import md.utm.gateway.proxy.ProxyService;

/**
 * Entry point for every client call to the API. The gateway exposes the same paths as the
 * API, so a client only changes the host it talks to.
 *
 * Connections from clients are kept alive by Tomcat (see server.tomcat.* in
 * application.properties); each request runs on its own (virtual) thread.
 */
@RestController
public class ProxyController {

    private final ProxyService proxy;

    public ProxyController(ProxyService proxy) {
        this.proxy = proxy;
    }

    @RequestMapping({"/api/**", "/whoami"})
    public void proxy(HttpServletRequest in, HttpServletResponse out) throws IOException {
        String requestId = in.getHeader("X-Request-Id") != null
                ? in.getHeader("X-Request-Id") : UUID.randomUUID().toString();
        out.setHeader("X-Request-Id", requestId);

        try {
            ProxyService.Result result = proxy.handle(toProxyRequest(in, requestId));
            out.setHeader("X-Cache", result.cacheStatus().name());
            write(result.response(), out);
        } catch (GatewayException e) {
            writeProblem(e, in.getHeader("Accept"), out);
        }
    }

    private static ProxyRequest toProxyRequest(HttpServletRequest in, String requestId) throws IOException {
        // headers named in "Connection: x, y" are hop-by-hop for this request only
        Set<String> connectionHeaders = new HashSet<>();
        for (String v : Collections.list(in.getHeaders("Connection"))) {
            for (String name : v.split(",")) connectionHeaders.add(name.trim().toLowerCase(Locale.ROOT));
        }

        List<Header> headers = new ArrayList<>();
        for (String name : Collections.list(in.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (Header.isHopByHop(name) || connectionHeaders.contains(lower)
                    || lower.startsWith("x-forwarded-") || lower.equals("x-request-id")) continue;
            for (String value : Collections.list(in.getHeaders(name))) {
                headers.add(new Header(name, value));
            }
        }

        String forwardedFor = in.getHeader("X-Forwarded-For");
        headers.add(new Header("X-Forwarded-For",
                forwardedFor == null ? in.getRemoteAddr() : forwardedFor + ", " + in.getRemoteAddr()));
        if (in.getHeader("Host") != null) headers.add(new Header("X-Forwarded-Host", in.getHeader("Host")));
        headers.add(new Header("X-Forwarded-Proto", in.getScheme()));
        headers.add(new Header("X-Request-Id", requestId));

        String path = in.getRequestURI();                     // raw, still URL-encoded
        String pathAndQuery = in.getQueryString() == null ? path : path + "?" + in.getQueryString();
        return new ProxyRequest(in.getMethod().toUpperCase(Locale.ROOT), path, pathAndQuery,
                List.copyOf(headers), in.getInputStream().readAllBytes());
    }

    private static void write(ProxyResponse resp, HttpServletResponse out) throws IOException {
        out.setStatus(resp.status());
        for (Header h : resp.headers()) {
            out.addHeader(h.name(), h.value());
        }
        out.setContentLength(resp.body().length);
        out.getOutputStream().write(resp.body());
    }

    /** RFC 7807 problem, in XML if the client prefers XML (like the API does). */
    private static void writeProblem(GatewayException e, String accept, HttpServletResponse out) throws IOException {
        int status = e.status();
        String title = switch (status) {
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> "Bad Gateway";
        };
        String detail = e.getMessage();
        boolean xml = accept != null && accept.contains("xml")
                && (!accept.contains("json") || accept.indexOf("xml") < accept.indexOf("json"));

        String body;
        if (xml) {
            out.setContentType("application/problem+xml");
            body = "<problem xmlns=\"urn:ietf:rfc:7807\"><title>" + escapeXml(title) + "</title><status>"
                    + status + "</status><detail>" + escapeXml(detail) + "</detail></problem>";
        } else {
            out.setContentType("application/problem+json");
            body = "{\"title\":\"" + escapeJson(title) + "\",\"status\":" + status
                    + ",\"detail\":\"" + escapeJson(detail) + "\"}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.setStatus(status);
        out.setContentLength(bytes.length);
        out.getOutputStream().write(bytes);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
