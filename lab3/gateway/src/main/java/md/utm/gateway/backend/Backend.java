package md.utm.gateway.backend;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One API instance (DW node) behind the gateway, with its live counters.
 * Shared by all request threads: every mutable field is atomic or volatile.
 *
 * Every instance belongs to one warehouse. Instances listed in gateway.backends are fixed;
 * instances the gateway started itself carry their container id and can be removed again.
 */
public final class Backend {

    public enum State { UP, STARTING, DOWN, DRAINING }

    private final String name;
    private final URI baseUri;
    private final String warehouse;
    private final String containerId;                 // null: listed in gateway.backends
    private final Integer hostPort;                   // published on the host for direct calls, or null

    private volatile boolean healthy;
    private volatile boolean seenUp;                  // passed at least one health check
    private volatile boolean retired;                 // removed from the registry, finishing its requests
    private volatile String lastError;

    private final AtomicInteger active = new AtomicInteger();   // requests in flight right now
    private final AtomicLong served = new AtomicLong();         // answered (any HTTP status)
    private final AtomicLong failed = new AtomicLong();         // I/O failures (no answer)

    /** A fixed backend in the default warehouse. */
    public Backend(URI baseUri) {
        this(baseUri, Warehouses.DEFAULT, null, true);
    }

    /**
     * @param startHealthy true for fixed backends (optimistic until the first health check);
     *                     false for a freshly started container, which needs time to boot
     */
    public Backend(URI baseUri, String warehouse, String containerId, boolean startHealthy) {
        this(baseUri, warehouse, containerId, null, startHealthy);
    }

    public Backend(URI baseUri, String warehouse, String containerId, Integer hostPort, boolean startHealthy) {
        String uri = baseUri.toString();
        this.baseUri = URI.create(uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri);
        this.name = baseUri.getHost() + (baseUri.getPort() > 0 ? ":" + baseUri.getPort() : "");
        this.warehouse = Warehouses.requireValid(warehouse);
        this.containerId = containerId;
        this.hostPort = hostPort;
        this.healthy = startHealthy;
    }

    public String name()        { return name; }
    public URI baseUri()        { return baseUri; }
    public String warehouse()   { return warehouse; }
    public String containerId() { return containerId; }
    public boolean isManaged()  { return containerId != null; }
    public Integer hostPort()   { return hostPort; }
    public boolean isHealthy()  { return healthy && !retired; }
    public boolean isRetired()  { return retired; }
    public String lastError()   { return lastError; }
    public int active()         { return active.get(); }
    public long served()        { return served.get(); }
    public long failed()        { return failed.get(); }

    public State state() {
        if (retired) return State.DRAINING;
        if (healthy) return State.UP;
        return seenUp ? State.DOWN : State.STARTING;
    }

    /** @return true if the state changed */
    public boolean markUp() {
        boolean changed = !healthy;
        healthy = true;
        seenUp = true;
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

    /**
     * Takes the backend out of service for good. Requests that already hold it finish;
     * {@link #tryAcquire()} refuses new ones.
     */
    void retire() {
        retired = true;
    }

    /**
     * Counts a request as in flight, unless the backend was retired in the meantime.
     *
     * The order matters: increment first, then read {@code retired}. The scaler does the
     * opposite (set {@code retired}, then read {@code active}). Both are volatile/atomic, so at
     * least one side sees the other: either this request backs off, or the scaler sees it in
     * flight and waits for it before stopping the container.
     */
    public boolean tryAcquire() {
        active.incrementAndGet();
        if (retired) {
            active.decrementAndGet();
            return false;
        }
        return true;
    }

    public void requestStarted()   { active.incrementAndGet(); }
    public void requestAnswered()  { active.decrementAndGet(); served.incrementAndGet(); }
    public void requestFailed()    { active.decrementAndGet(); failed.incrementAndGet(); }

    @Override
    public String toString() { return name + " (" + warehouse + ")"; }
}
