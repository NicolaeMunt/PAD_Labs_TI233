package md.utm.gateway.proxy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Counters shown on /gateway/state. LongAdder: cheap increments from many threads at once. */
public class GatewayStats {

    final LongAdder requests = new LongAdder();
    final LongAdder cacheHits = new LongAdder();
    final LongAdder cacheMisses = new LongAdder();
    final LongAdder coalesced = new LongAdder();
    final LongAdder invalidations = new LongAdder();
    final LongAdder failovers = new LongAdder();
    final LongAdder errors = new LongAdder();

    public long cacheHits()   { return cacheHits.sum(); }
    public long cacheMisses() { return cacheMisses.sum(); }
    public long coalesced()   { return coalesced.sum(); }
    public long failovers()   { return failovers.sum(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("requests", requests.sum());
        m.put("cacheHits", cacheHits.sum());
        m.put("cacheMisses", cacheMisses.sum());
        m.put("coalesced", coalesced.sum());
        m.put("invalidations", invalidations.sum());
        m.put("failovers", failovers.sum());
        m.put("errors", errors.sum());
        return m;
    }
}
