package md.utm.gateway.balancer;

import java.util.List;

import md.utm.gateway.backend.Backend;

/** Picks which API instance serves the next request. Must be safe to call from many threads. */
public interface LoadBalancer {

    /** @param candidates non-empty list of backends that may be used */
    Backend choose(List<Backend> candidates);

    String name();
}
