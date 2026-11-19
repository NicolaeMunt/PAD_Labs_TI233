package md.utm.gateway.balancer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import md.utm.gateway.backend.Backend;

/**
 * Takes the backends in turn: 1, 2, 3, 1, 2, 3, ...
 * Lock-free: one shared counter, {@code getAndIncrement} gives every thread its own ticket.
 */
public class RoundRobinBalancer implements LoadBalancer {

    private final AtomicInteger next = new AtomicInteger();

    @Override
    public Backend choose(List<Backend> candidates) {
        // floorMod keeps the index valid after the counter overflows to negative values
        return candidates.get(Math.floorMod(next.getAndIncrement(), candidates.size()));
    }

    @Override
    public String name() {
        return "round-robin";
    }
}
