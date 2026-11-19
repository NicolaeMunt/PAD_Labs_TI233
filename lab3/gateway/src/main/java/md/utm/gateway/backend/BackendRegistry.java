package md.utm.gateway.backend;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All API instances, grouped by warehouse. Instances are added and removed at runtime
 * (by {@link md.utm.gateway.scaling.WarehouseScaler}) while request threads read the list.
 *
 * Reads happen on every request, writes only when someone scales, so the list is a
 * {@link CopyOnWriteArrayList}: readers never lock and always see a complete snapshot.
 * Writers are serialized ({@code synchronized}) so the duplicate-name check and the add are atomic.
 */
public class BackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistry.class);

    private final CopyOnWriteArrayList<Backend> all = new CopyOnWriteArrayList<>();

    /** Fixed backends, all in the default warehouse. */
    public static BackendRegistry of(List<URI> uris) {
        BackendRegistry r = new BackendRegistry();
        uris.forEach(u -> r.register(new Backend(u)));
        return r;
    }

    /**
     * Fixed backends from gateway.backends. Each entry is either a URL (default warehouse)
     * or {@code warehouse=URL}, e.g. {@code warehouse-2=http://api-2:8080}.
     */
    public static BackendRegistry fromConfig(List<String> specs, String defaultWarehouse) {
        BackendRegistry r = new BackendRegistry();
        for (String spec : specs == null ? List.<String>of() : specs) {
            String s = spec.trim();
            if (s.isEmpty()) continue;
            int eq = s.indexOf('=');
            int scheme = s.indexOf("://");
            boolean named = eq > 0 && (scheme < 0 || eq < scheme);
            String warehouse = named ? s.substring(0, eq) : defaultWarehouse;
            URI uri = URI.create(named ? s.substring(eq + 1).trim() : s);
            r.register(new Backend(uri, warehouse, null, true));
        }
        return r;
    }

    /** A snapshot: later changes to the registry do not show up in it. */
    public List<Backend> all() {
        return List.copyOf(all);
    }

    public List<Backend> healthy() {
        return all.stream().filter(Backend::isHealthy).toList();
    }

    public List<Backend> inWarehouse(String warehouse) {
        return all.stream().filter(b -> b.warehouse().equals(warehouse)).toList();
    }

    public List<Backend> healthy(String warehouse) {
        return all.stream().filter(b -> b.warehouse().equals(warehouse) && b.isHealthy()).toList();
    }

    public SortedSet<String> warehouses() {
        return all.stream().map(Backend::warehouse).collect(Collectors.toCollection(TreeSet::new));
    }

    public Optional<Backend> find(String name) {
        return all.stream().filter(b -> b.name().equals(name)).findFirst();
    }

    public synchronized void register(Backend b) {
        if (find(b.name()).isPresent()) {
            throw new IllegalStateException("Backend " + b.name() + " is already registered");
        }
        all.add(b);
        log.info("Backend {} added", b);
    }

    /**
     * Removes the backend and retires it, so requests that picked it from an older
     * snapshot back off ({@link Backend#tryAcquire()}).
     */
    public synchronized boolean remove(Backend b) {
        b.retire();
        boolean removed = all.remove(b);
        if (removed) log.info("Backend {} removed", b);
        return removed;
    }

    public void markUp(Backend b) {
        if (b.markUp()) log.info("Backend {} is UP", b);
    }

    public void markDown(Backend b, String reason) {
        if (b.markDown(reason)) log.warn("Backend {} is DOWN: {}", b, reason);
    }
}
