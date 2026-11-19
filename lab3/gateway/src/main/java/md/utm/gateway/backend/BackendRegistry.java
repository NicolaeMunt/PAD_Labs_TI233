package md.utm.gateway.backend;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The fixed list of API instances. The list never changes, only each backend's state. */
public class BackendRegistry {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistry.class);

    private final List<Backend> all;

    public BackendRegistry(List<URI> uris) {
        if (uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("gateway.backends must list at least one API instance");
        }
        this.all = uris.stream().map(Backend::new).toList();
    }

    public List<Backend> all() {
        return all;
    }

    public List<Backend> healthy() {
        return all.stream().filter(Backend::isHealthy).toList();
    }

    public void markUp(Backend b) {
        if (b.markUp()) log.info("Backend {} is UP", b);
    }

    public void markDown(Backend b, String reason) {
        if (b.markDown(reason)) log.warn("Backend {} is DOWN: {}", b, reason);
    }
}
