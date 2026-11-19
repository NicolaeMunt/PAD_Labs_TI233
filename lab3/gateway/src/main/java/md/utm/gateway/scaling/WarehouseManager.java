package md.utm.gateway.scaling;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import md.utm.gateway.backend.Backend;
import md.utm.gateway.backend.BackendRegistry;
import md.utm.gateway.backend.Warehouses;
import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.scaling.InstanceProvisioner.DbNode;
import md.utm.gateway.scaling.InstanceProvisioner.Instance;
import md.utm.gateway.scaling.InstanceProvisioner.Inventory;

/**
 * Horizontal scaling of warehouses. A warehouse with {@code n} API instances gets its own
 * MongoDB replica set with {@code max(minDbCopies, n)} copies of its database (see {@link Names}).
 *
 * <p><b>Desired state + reconcile.</b> A client request only records the desired number of API
 * instances ("latest wins") and schedules a reconcile of that warehouse; it answers at once.
 * Each warehouse has one reconcile thread, so its steps never overlap, while different warehouses
 * scale in parallel. A reconcile reads the latest desired count and brings the containers in line:
 * <ol>
 *   <li>scale up: start the missing Mongo nodes, initiate the replica set (first time) or add the
 *       new nodes to it, then start the missing API instances (API i reads from node i);</li>
 *   <li>scale down: take the API instances with the highest index out of rotation, let them finish
 *       their requests, stop them; then remove the extra Mongo nodes from the replica set (a primary
 *       steps down first) and delete them with their data volume;</li>
 *   <li>delete: as scale down to zero, and every Mongo node and volume is removed.</li>
 * </ol>
 * A failed step leaves the warehouse in phase ERROR with the reason; the next request retries.
 *
 * <p>Warehouses whose instances are listed in gateway.backends are fixed and cannot be scaled.
 * A status monitor polls every replica set, so the UI shows which node is PRIMARY.
 */
public class WarehouseManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WarehouseManager.class);
    private static final String STARTING = "STARTUP";

    public enum Phase { READY, SCALING, ERROR, DELETING }

    public record ScaleAccepted(String warehouse, int before, int desiredReplicas, int desiredDbCopies) { }

    public record DbNodeView(String name, int index, String host, String state) { }

    public record WarehouseView(String name, String database, String replicaSet, int desiredReplicas,
                                String phase, String lastError, List<Backend> instances, List<DbNodeView> dbNodes) { }

    /** One managed warehouse. Fields are written by its reconcile thread and read by any thread. */
    private static final class WarehouseState {
        final String name;
        final ExecutorService worker;
        final AtomicInteger pending = new AtomicInteger();          // reconciles queued or running
        final Map<Integer, DbNode> nodes = new ConcurrentHashMap<>();
        volatile Map<Integer, String> nodeStates = Map.of();         // from the status monitor
        volatile int desired;
        volatile boolean deleting;
        volatile boolean initiated;                                   // replica set known to exist
        volatile Phase phase = Phase.SCALING;
        volatile String lastError;

        WarehouseState(String name, int desired) {
            this.name = name;
            this.desired = desired;
            this.worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("reconcile-" + name).factory());
        }
    }

    private final BackendRegistry registry;
    private final InstanceProvisioner provisioner;
    private final ReplicaSet replicaSet;
    private final GatewayProperties props;
    private final GatewayProperties.Provisioner cfg;

    private final ConcurrentHashMap<String, WarehouseState> warehouses = new ConcurrentHashMap<>();
    /** makes "check the warehouse limit, then create" atomic */
    private final Object createLock = new Object();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("replica-set-monitor").daemon().factory());

    public WarehouseManager(BackendRegistry registry, InstanceProvisioner provisioner, ReplicaSet replicaSet,
                            GatewayProperties props) {
        this.registry = registry;
        this.provisioner = provisioner;
        this.replicaSet = replicaSet;
        this.props = props;
        this.cfg = props.provisioner();
    }

    public boolean isEnabled()  { return provisioner.isEnabled(); }
    public int maxReplicas()    { return cfg.maxReplicas(); }
    public int minDbCopies()    { return cfg.minDbCopies(); }

    // ------------------------------------------------------------------ startup

    /**
     * Takes back the containers of an earlier run (by their labels), creates the initial
     * warehouses that do not exist yet, and starts the replica-set monitor.
     */
    public void start() {
        if (!provisioner.isEnabled()) return;
        adoptExisting();
        for (String raw : props.initialWarehouses()) {
            String w = Warehouses.normalize(raw);
            if (w == null || warehouses.containsKey(w) || isStatic(w)) continue;
            log.info("Creating initial warehouse {}", w);
            WarehouseState s = new WarehouseState(w, 1);
            warehouses.put(w, s);
            schedule(s);
        }
        monitor.scheduleWithFixedDelay(this::pollStatus, 3, 3, TimeUnit.SECONDS);
    }

    void adoptExisting() {
        Inventory inv;
        try {
            inv = provisioner.discover();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not list existing containers (is the Docker socket mounted?): {}", e.toString());
            return;
        }
        Map<String, List<Instance>> apis = new HashMap<>();
        Map<String, List<DbNode>> nodes = new HashMap<>();
        inv.apis().forEach(i -> apis.computeIfAbsent(i.warehouse(), k -> new ArrayList<>()).add(i));
        inv.dbNodes().forEach(n -> nodes.computeIfAbsent(n.warehouse(), k -> new ArrayList<>()).add(n));

        TreeSet<String> names = new TreeSet<>(apis.keySet());
        names.addAll(nodes.keySet());
        for (String w : names) {
            if (Warehouses.normalize(w) == null || isStatic(w)) continue;
            List<Instance> wApis = apis.getOrDefault(w, List.of());
            // nodes without API instances: the data is still there, so bring back one instance
            WarehouseState s = new WarehouseState(w, Math.max(1, wApis.size()));
            nodes.getOrDefault(w, List.of()).forEach(n -> s.nodes.put(n.index(), n));
            for (Instance i : wApis) {
                Backend b = new Backend(i.baseUri(), w, i.id(), i.hostPort(), false);
                if (registry.find(b.name()).isEmpty()) registry.register(b);
            }
            warehouses.put(w, s);
            log.info("Adopted warehouse {}: {} API instance(s), {} database node(s)", w, wApis.size(), s.nodes.size());
            schedule(s);                       // checks the replica set and fills any gap
        }
    }

    // ------------------------------------------------------------------ client requests

    public ScaleAccepted scale(String rawWarehouse, int replicas) {
        String w = validName(rawWarehouse);
        requireEnabled();
        if (replicas == 0) {
            throw new ScalingException(400, "replicas must be at least 1; use DELETE to remove a warehouse");
        }
        if (replicas < 0 || replicas > cfg.maxReplicas()) {
            throw new ScalingException(400, "replicas must be between 1 and " + cfg.maxReplicas());
        }
        if (isStatic(w)) {
            throw new ScalingException(409, "Warehouse '" + w + "' is configured in gateway.backends and cannot be scaled");
        }

        WarehouseState s;
        synchronized (createLock) {
            s = warehouses.get(w);
            if (s == null) {
                if (allWarehouseNames().size() >= cfg.maxWarehouses()) {
                    throw new ScalingException(409, "There are already " + cfg.maxWarehouses() + " warehouses (the limit)");
                }
                s = new WarehouseState(w, replicas);
                warehouses.put(w, s);
            }
        }
        if (s.deleting) throw new ScalingException(409, "Warehouse '" + w + "' is being deleted");

        int before = apiInstances(w).size();
        s.desired = replicas;
        schedule(s);
        log.info("Warehouse {}: {} → {} API instance(s), {} database copies", w, before, replicas, dbCopies(replicas));
        return new ScaleAccepted(w, before, replicas, dbCopies(replicas));
    }

    /** Deletes the warehouse: its API instances, its Mongo nodes and their data. */
    public ScaleAccepted remove(String rawWarehouse) {
        String w = validName(rawWarehouse);
        requireEnabled();
        if (w.equals(props.defaultWarehouse())) {
            throw new ScalingException(409, "The default warehouse '" + w + "' cannot be deleted");
        }
        if (isStatic(w)) {
            throw new ScalingException(409, "Warehouse '" + w + "' is configured in gateway.backends and cannot be deleted");
        }
        WarehouseState s = warehouses.get(w);
        if (s == null) throw new ScalingException(404, "Unknown warehouse '" + w + "'");

        int before = apiInstances(w).size();
        s.deleting = true;
        s.desired = 0;
        schedule(s);
        log.info("Warehouse {}: deleting ({} API instance(s), {} database node(s))", w, before, s.nodes.size());
        return new ScaleAccepted(w, before, 0, 0);
    }

    public List<WarehouseView> view() {
        List<WarehouseView> views = new ArrayList<>();
        for (String w : allWarehouseNames()) {
            List<Backend> instances = new ArrayList<>(registry.inWarehouse(w));
            instances.sort(Comparator.comparingInt(WarehouseManager::apiIndex).thenComparing(Backend::name));
            WarehouseState s = warehouses.get(w);
            if (s == null) {                                          // fixed, from gateway.backends
                views.add(new WarehouseView(w, Names.database(w), null, instances.size(), Phase.READY.name(),
                        null, instances, List.of()));
                continue;
            }
            Map<Integer, String> states = s.nodeStates;
            List<DbNodeView> nodes = new TreeMap<>(s.nodes).values().stream()
                    .map(n -> new DbNodeView(n.name(), n.index(), n.host(), states.getOrDefault(n.index(), STARTING)))
                    .toList();
            views.add(new WarehouseView(w, Names.database(w), Names.replicaSet(w), s.desired, s.phase.name(),
                    s.lastError, instances, nodes));
        }
        return views;
    }

    // ------------------------------------------------------------------ reconcile

    private void schedule(WarehouseState s) {
        s.pending.incrementAndGet();
        s.phase = s.deleting ? Phase.DELETING : Phase.SCALING;
        try {
            s.worker.submit(() -> runReconcile(s));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            s.pending.decrementAndGet();                              // shutting down
        }
    }

    private void runReconcile(WarehouseState s) {
        boolean deleted = false;
        try {
            reconcile(s);
            s.lastError = null;
            deleted = s.deleting;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            s.lastError = "interrupted";
        } catch (Exception e) {
            s.lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Reconciling {} failed: {}", s.name, s.lastError);
        } finally {
            if (s.pending.decrementAndGet() == 0) {
                if (deleted) {
                    warehouses.remove(s.name, s);
                    s.worker.shutdown();
                    log.info("Warehouse {} deleted", s.name);
                } else {
                    s.phase = s.lastError == null ? Phase.READY : Phase.ERROR;
                }
            }
        }
    }

    /** Brings the warehouse's containers in line with its desired count. Runs on its reconcile thread. */
    void reconcile(WarehouseState s) throws IOException, InterruptedException {
        String w = s.name;
        int desired = s.deleting ? 0 : s.desired;
        int copies = dbCopies(desired);
        Duration timeout = cfg.mongoTimeout();

        if (desired > 0) {
            // 1. database copies first, so new API instances find their node
            List<Integer> started = new ArrayList<>();
            for (int i = 1; i <= copies; i++) {
                if (!s.nodes.containsKey(i)) {
                    s.nodes.put(i, provisioner.startDbNode(w, i));
                    started.add(i);
                }
            }
            if (!s.initiated) {
                for (DbNode n : s.nodes.values()) replicaSet.awaitReachable(n.host(), timeout);
                Map<Integer, String> members = new TreeMap<>();
                s.nodes.values().forEach(n -> members.put(n.index(), n.host()));
                replicaSet.initiate(w, members, timeout);            // no-op if it already exists
                s.initiated = true;
            }
            for (int i : started) {
                DbNode n = s.nodes.get(i);
                replicaSet.awaitReachable(n.host(), timeout);
                replicaSet.addMember(w, seeds(s, i), i, n.host(), timeout);   // no-op if already a member
            }

            // 2. API instances 1..desired
            for (int i = 1; i <= desired; i++) {
                if (findApi(w, i) == null) {
                    Instance inst = provisioner.startApi(w, i, Names.mongoUri(w, hosts(s), i));
                    registry.register(new Backend(inst.baseUri(), w, inst.id(), inst.hostPort(), false));
                }
            }
        }

        // 3. API instances above the desired count: out of rotation, drain, stop (in parallel)
        List<Backend> extra = apiInstances(w).stream().filter(b -> apiIndex(b) > desired).toList();
        if (!extra.isEmpty()) {
            extra.forEach(registry::remove);
            try (var stoppers = Executors.newVirtualThreadPerTaskExecutor()) {
                for (Backend b : extra) stoppers.submit(() -> drainAndStop(b));
            }
        }

        // 4. Mongo nodes above the desired copies, highest index first
        List<Integer> surplus = s.nodes.keySet().stream().filter(i -> i > copies)
                .sorted(Comparator.reverseOrder()).toList();
        for (int i : surplus) {
            DbNode n = s.nodes.get(i);
            if (!s.deleting) {
                replicaSet.removeMember(w, seeds(s, i), n.host(), timeout);   // primary steps down first
            }
            provisioner.removeDbNode(n);
            s.nodes.remove(i);
        }
    }

    /** Waits until the retired instance has no request in flight (or the drain timeout), then stops it. */
    private void drainAndStop(Backend b) {
        long deadline = System.nanoTime() + cfg.drainTimeout().toNanos();
        try {
            while (b.active() > 0 && System.nanoTime() < deadline) Thread.sleep(50);
            if (b.active() > 0) log.warn("{} still has {} request(s) after the drain timeout", b, b.active());
            provisioner.stopApi(b.containerId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            log.warn("Could not stop {}: {}", b, e.toString());
        }
    }

    // ------------------------------------------------------------------ replica-set monitor

    /** Asks every replica set which node is PRIMARY / SECONDARY. Runs every few seconds. */
    void pollStatus() {
        try (var polls = Executors.newVirtualThreadPerTaskExecutor()) {
            for (WarehouseState s : warehouses.values()) {
                if (s.nodes.isEmpty() || s.deleting || !s.initiated) continue;
                polls.submit(() -> {
                    Map<Integer, String> states = new HashMap<>();
                    try {
                        ReplicaSet.Status status = replicaSet.status(s.name, hosts(s));
                        Map<String, ReplicaSet.MemberState> byHost = new HashMap<>();
                        status.members().forEach(m -> byHost.put(m.host(), m.state()));
                        for (DbNode n : s.nodes.values()) {
                            ReplicaSet.MemberState st = byHost.get(n.host());
                            states.put(n.index(), st == null ? STARTING : st.name());
                        }
                    } catch (Exception e) {
                        s.nodes.keySet().forEach(i -> states.put(i, ReplicaSet.MemberState.UNKNOWN.name()));
                    }
                    s.nodeStates = Map.copyOf(states);
                });
            }
        } catch (RuntimeException e) {
            log.debug("Replica-set status poll failed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    private int dbCopies(int replicas) {
        return Names.dbCopies(replicas, cfg.minDbCopies());
    }

    private static List<String> hosts(WarehouseState s) {
        return new TreeMap<>(s.nodes).values().stream().map(DbNode::host).toList();
    }

    /** All known nodes except {@code except}: where to find the current primary. */
    private static List<String> seeds(WarehouseState s, int except) {
        return new TreeMap<>(s.nodes).values().stream().filter(n -> n.index() != except).map(DbNode::host).toList();
    }

    private List<Backend> apiInstances(String w) {
        return registry.inWarehouse(w).stream().filter(Backend::isManaged).toList();
    }

    private Backend findApi(String w, int index) {
        return apiInstances(w).stream().filter(b -> apiIndex(b) == index).findFirst().orElse(null);
    }

    /** api-{warehouse}-{i} → i; 0 for anything else (fixed backends). */
    static int apiIndex(Backend b) {
        String host = b.baseUri().getHost();
        int dash = host.lastIndexOf('-');
        if (!host.startsWith("api-" + b.warehouse() + "-") || dash < 0) return 0;
        try {
            return Integer.parseInt(host.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isStatic(String w) {
        return registry.inWarehouse(w).stream().anyMatch(b -> !b.isManaged());
    }

    private TreeSet<String> allWarehouseNames() {
        TreeSet<String> names = new TreeSet<>(registry.warehouses());
        names.addAll(warehouses.keySet());
        return names;
    }

    private void requireEnabled() {
        if (!provisioner.isEnabled()) {
            throw new ScalingException(501, "Scaling is disabled: set gateway.provisioner.enabled=true "
                    + "and mount the Docker socket into the gateway");
        }
    }

    private static String validName(String raw) {
        try {
            return Warehouses.requireValid(raw);
        } catch (IllegalArgumentException e) {
            throw new ScalingException(400, e.getMessage());
        }
    }

    /** For tests: waits until no reconcile is queued or running. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (warehouses.values().stream().allMatch(s -> s.pending.get() == 0)) return true;
            Thread.sleep(10);
        }
        return false;
    }

    /** The containers keep running; the next start adopts them. */
    @Override
    public void close() throws InterruptedException {
        monitor.shutdownNow();
        for (WarehouseState s : warehouses.values()) s.worker.shutdown();
        for (WarehouseState s : warehouses.values()) {
            if (!s.worker.awaitTermination(cfg.drainTimeout().plusSeconds(15).toMillis(), TimeUnit.MILLISECONDS)) {
                s.worker.shutdownNow();
            }
        }
    }
}
