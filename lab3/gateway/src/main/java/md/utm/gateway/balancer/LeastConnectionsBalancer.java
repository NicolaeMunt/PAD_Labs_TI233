package md.utm.gateway.balancer;

import java.util.ArrayList;
import java.util.List;

import md.utm.gateway.backend.Backend;

/**
 * Sends the request to the backend with the fewest requests in flight.
 * Ties are broken round-robin, so idle backends still share the load evenly.
 */
public class LeastConnectionsBalancer implements LoadBalancer {

    private final RoundRobinBalancer tieBreaker = new RoundRobinBalancer();

    @Override
    public Backend choose(List<Backend> candidates) {
        int min = Integer.MAX_VALUE;
        List<Backend> best = new ArrayList<>();
        for (Backend b : candidates) {
            int active = b.active();          // a snapshot; other threads may change it right after
            if (active < min) {
                min = active;
                best.clear();
            }
            if (active == min) best.add(b);
        }
        return tieBreaker.choose(best);
    }

    @Override
    public String name() {
        return "least-connections";
    }
}
