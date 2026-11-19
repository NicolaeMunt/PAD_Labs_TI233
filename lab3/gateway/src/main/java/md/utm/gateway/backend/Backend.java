package md.utm.gateway.backend;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One API instance (DW node) behind the gateway, with its live counters.
 * Shared by all request threads: every mutable field is atomic or volatile.
 */
public final class Backend {

    private final String name;
    private final URI baseUri;

    private volatile boolean healthy = true;          // optimistic until the first health check
    private volatile String lastError;

    private final AtomicInteger active = new AtomicInteger();   // requests in flight right now
    private final AtomicLong served = new AtomicLong();         // answered (any HTTP status)
    private final AtomicLong failed = new AtomicLong();         // I/O failures (no answer)

    public Backend(URI baseUri) {
        String uri = baseUri.toString();
        this.baseUri = URI.create(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
        this.name = baseUri.getHost() + (baseUri.getPort() > 0 ? ":" + baseUri.getPort() : "");
    }

    public String name()       { return name; }
    public URI baseUri()       { return baseUri; }
    public boolean isHealthy() { return healthy; }
    public String lastError()  { return lastError; }
    public int active()        { return active.get(); }
    public long served()       { return served.get(); }
    public long failed()       { return failed.get(); }

    /** @return true if the state changed */
    public boolean markUp() {
        boolean changed = !healthy;
        healthy = true;
        lastError = null;
        return changed;
    }

    /** @return true if the state changed */
    public boolean markDown(String reason) {
        boolean changed = healthy;
        healthy = false;
        lastError = reason;
        return changed;
    }

    public void requestStarted()   { active.incrementAndGet(); }
    public void requestAnswered()  { active.decrementAndGet(); served.incrementAndGet(); }
    public void requestFailed()    { active.decrementAndGet(); failed.incrementAndGet(); }

    @Override
    public String toString() { return name; }
}
