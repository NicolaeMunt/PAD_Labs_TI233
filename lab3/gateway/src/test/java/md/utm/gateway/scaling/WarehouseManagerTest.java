package md.utm.gateway.scaling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import md.utm.gateway.backend.Backend;
import md.utm.gateway.backend.BackendRegistry;
import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.scaling.WarehouseManager.WarehouseView;

/** The reconcile logic against an in-memory Docker and an in-memory replica set. */
class WarehouseManagerTest {

    /** Pretends to run containers. */
    static class FakeProvisioner implements InstanceProvisioner {
        final Map<String, Instance> apis = new ConcurrentHashMap<>();         // by container id
        final Map<String, DbNode> nodes = new ConcurrentHashMap<>();          // by container id
        final List<String> log = new CopyOnWriteArrayList<>();
        final Map<String, String> mongoUris = new ConcurrentHashMap<>();      // api name → uri
        volatile String failApi;

        @Override public boolean isEnabled() { return true; }

        @Override
        public Instance startApi(String w, int i, String mongoUri) throws IOException {
            pause();
            String name = Names.apiContainer(w, i);
            if (name.equals(failApi)) throw new IOException("no space left on device");
            int port = 8081;
            Set<Integer> used = ConcurrentHashMap.newKeySet();
            apis.values().forEach(a -> used.add(a.hostPort()));
            while (used.contains(port)) port++;
            Instance inst = new Instance("id-" + name, name, w, i, URI.create("http://" + name + ":8080"), port);
            apis.put(inst.id(), inst);
            mongoUris.put(name, mongoUri);
            log.add("startApi " + name);
            return inst;
        }

        @Override
        public void stopApi(String id) {
            Instance gone = apis.remove(id);
            log.add("stopApi " + (gone == null ? id : gone.name()));
        }

        @Override
        public DbNode startDbNode(String w, int i) {
            pause();
            DbNode n = new DbNode("id-" + Names.mongoContainer(w, i), Names.mongoContainer(w, i), w, i, Names.mongoHost(w, i));
            nodes.put(n.id(), n);
            log.add("startDbNode " + n.name());
            return n;
        }

        @Override
        public void removeDbNode(DbNode n) {
            nodes.remove(n.id());
            log.add("removeDbNode " + n.name());
        }

        @Override
        public Inventory discover() {
            return new Inventory(List.copyOf(apis.values()), List.copyOf(nodes.values()));
        }

        long apiCount(String w)  { return apis.values().stream().filter(a -> a.warehouse().equals(w)).count(); }
        long nodeCount(String w) { return nodes.values().stream().filter(n -> n.warehouse().equals(w)).count(); }
    }

    /** One replica set per warehouse; the lowest member is primary. */
    static class FakeReplicaSet implements ReplicaSet {
        final Map<String, TreeMap<Integer, String>> sets = new ConcurrentHashMap<>();
        final List<String> log = new CopyOnWriteArrayList<>();

        @Override public void awaitReachable(String host, Duration t) { }

        @Override
        public void initiate(String w, Map<Integer, String> members, Duration t) {
            if (sets.putIfAbsent(w, new TreeMap<>(members)) == null) log.add("initiate " + w + " " + members.keySet());
        }

        @Override
        public synchronized void addMember(String w, Collection<String> seeds, int index, String host, Duration t) throws IOException {
            TreeMap<Integer, String> set = sets.get(w);
            if (set == null) throw new IOException("not initiated");
            if (set.containsValue(host)) return;
            set.put(index, host);
            log.add("add " + w + " " + index);
        }

        @Override
        public synchronized void removeMember(String w, Collection<String> seeds, String host, Duration t) {
            TreeMap<Integer, String> set = sets.get(w);
            if (set != null && set.values().remove(host)) log.add("remove " + w + " " + host);
        }

        @Override
        public synchronized Status status(String w, Collection<String> seeds) throws IOException {
            TreeMap<Integer, String> set = sets.get(w);
            if (set == null) throw new IOException("not initiated");
            List<Member> members = new ArrayList<>();
            set.forEach((i, h) -> members.add(new Member(i, h,
                    i.equals(set.firstKey()) ? MemberState.PRIMARY : MemberState.SECONDARY, true)));
            return new Status(Names.replicaSet(w), members, set.firstEntry().getValue());
        }
    }

    private BackendRegistry registry;
    private FakeProvisioner docker;
    private FakeReplicaSet mongo;
    private WarehouseManager manager;

    @BeforeEach
    void setUp() {
        registry = new BackendRegistry();
        docker = new FakeProvisioner();
        mongo = new FakeReplicaSet();
        manager = new WarehouseManager(registry, docker, mongo, props(true, List.of()));
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    @Test
    void fiveInstancesGetFiveDatabaseCopiesInTheirOwnReplicaSet() throws Exception {
        var accepted = manager.scale("warehouse-4", 5);
        assertThat(accepted.desiredDbCopies()).isEqualTo(5);
        idle();

        assertThat(docker.nodeCount("warehouse-4")).isEqualTo(5);
        assertThat(docker.apiCount("warehouse-4")).isEqualTo(5);
        assertThat(mongo.log).containsExactly("initiate warehouse-4 [1, 2, 3, 4, 5]");
        assertThat(registry.inWarehouse("warehouse-4")).hasSize(5);

        // API i reads from node i, all writes go to the replica set rs-warehouse-4
        String uri = docker.mongoUris.get("api-warehouse-4-3");
        assertThat(uri).contains("mongo-warehouse-4-1:27017").contains("mongo-warehouse-4-5:27017")
                .contains("replicaSet=rs-warehouse-4").contains("w=majority")
                .contains("readPreferenceTags=node:3&readPreferenceTags=");

        WarehouseView view = view("warehouse-4");
        assertThat(view.phase()).isEqualTo("READY");
        assertThat(view.replicaSet()).isEqualTo("rs-warehouse-4");
        assertThat(view.dbNodes()).extracting(WarehouseManager.DbNodeView::name)
                .containsExactly("mongo-warehouse-4-1", "mongo-warehouse-4-2", "mongo-warehouse-4-3",
                                 "mongo-warehouse-4-4", "mongo-warehouse-4-5");
    }

    @Test
    void neverFewerThanThreeCopies() throws Exception {
        assertThat(manager.scale("warehouse-2", 1).desiredDbCopies()).isEqualTo(3);
        idle();
        assertThat(docker.nodeCount("warehouse-2")).isEqualTo(3);
        assertThat(docker.apiCount("warehouse-2")).isEqualTo(1);
    }

    @Test
    void scaleUpStartsNodesFirstAndAddsThemToTheExistingSet() throws Exception {
        manager.scale("warehouse-2", 3);
        idle();
        docker.log.clear();
        manager.scale("warehouse-2", 5);
        idle();

        assertThat(mongo.log).containsExactly("initiate warehouse-2 [1, 2, 3]", "add warehouse-2 4", "add warehouse-2 5");
        assertThat(docker.log).containsExactly(
                "startDbNode mongo-warehouse-2-4", "startDbNode mongo-warehouse-2-5",
                "startApi api-warehouse-2-4", "startApi api-warehouse-2-5");
    }

    @Test
    void scaleDownStopsTheNewestInstancesThenRemovesTheirNodes() throws Exception {
        manager.scale("warehouse-2", 5);
        idle();
        docker.log.clear();
        manager.scale("warehouse-2", 2);
        idle();

        assertThat(docker.apiCount("warehouse-2")).isEqualTo(2);
        assertThat(docker.nodeCount("warehouse-2")).isEqualTo(3);                // minimum 3 copies
        assertThat(mongo.log).containsSubsequence("remove warehouse-2 mongo-warehouse-2-5:27017",
                                                  "remove warehouse-2 mongo-warehouse-2-4:27017");
        assertThat(docker.log).containsExactlyInAnyOrder(
                "stopApi api-warehouse-2-3", "stopApi api-warehouse-2-4", "stopApi api-warehouse-2-5",
                "removeDbNode mongo-warehouse-2-5", "removeDbNode mongo-warehouse-2-4");
        // API instances are gone before their nodes are removed
        assertThat(docker.log.indexOf("removeDbNode mongo-warehouse-2-5"))
                .isGreaterThan(docker.log.indexOf("stopApi api-warehouse-2-5"));
        assertThat(registry.inWarehouse("warehouse-2")).extracting(b -> b.baseUri().getHost())
                .containsExactlyInAnyOrder("api-warehouse-2-1", "api-warehouse-2-2");
    }

    @Test
    void scaleDownWaitsForRequestsInFlight() throws Exception {
        manager.scale("warehouse-2", 2);
        idle();
        Backend second = registry.inWarehouse("warehouse-2").stream()
                .filter(b -> b.baseUri().getHost().equals("api-warehouse-2-2")).findFirst().orElseThrow();
        assertThat(second.tryAcquire()).isTrue();                 // a request is running on it

        manager.scale("warehouse-2", 1);
        Thread.sleep(300);
        assertThat(docker.log).doesNotContain("stopApi api-warehouse-2-2");
        second.requestAnswered();
        idle();
        assertThat(docker.log).contains("stopApi api-warehouse-2-2");
    }

    @Test
    void deleteRemovesEverythingIncludingTheData() throws Exception {
        manager.scale("warehouse-5", 4);
        idle();
        manager.remove("warehouse-5");
        idle();

        assertThat(docker.apiCount("warehouse-5")).isZero();
        assertThat(docker.nodeCount("warehouse-5")).isZero();
        assertThat(registry.inWarehouse("warehouse-5")).isEmpty();
        assertThat(manager.view()).extracting(WarehouseView::name).doesNotContain("warehouse-5");
        assertThat(mongo.log).noneMatch(l -> l.startsWith("remove warehouse-5"));   // no reconfig, just deleted
    }

    @Test
    void invalidRequestsAreRejected() {
        assertThat(status(() -> manager.scale("warehouse-2", 0))).isEqualTo(400);
        assertThat(status(() -> manager.scale("warehouse-2", 6))).isEqualTo(400);
        assertThat(status(() -> manager.scale("Bad Name!", 1))).isEqualTo(400);
        assertThat(status(() -> manager.remove("warehouse-1"))).isEqualTo(409);    // the default one
        assertThat(status(() -> manager.remove("warehouse-9"))).isEqualTo(404);

        WarehouseManager off = new WarehouseManager(registry, InstanceProvisioner.DISABLED, mongo, props(false, List.of()));
        assertThat(status(() -> off.scale("warehouse-2", 1))).isEqualTo(501);
    }

    @Test
    void warehousesFromGatewayBackendsCannotBeScaled() {
        registry.register(new Backend(URI.create("http://api-1:8080"), "warehouse-1", null, true));
        assertThat(status(() -> manager.scale("warehouse-1", 3))).isEqualTo(409);
    }

    @Test
    void warehouseLimitIsEnforced() {
        for (int i = 1; i <= 5; i++) manager.scale("w" + i, 1);
        assertThat(status(() -> manager.scale("w6", 1))).isEqualTo(409);
    }

    @Test
    void aFailureIsShownAndTheNextRequestRetries() throws Exception {
        docker.failApi = "api-warehouse-3-2";
        manager.scale("warehouse-3", 2);
        idle();
        WarehouseView failed = view("warehouse-3");
        assertThat(failed.phase()).isEqualTo("ERROR");
        assertThat(failed.lastError()).contains("no space left on device");

        docker.failApi = null;
        manager.scale("warehouse-3", 2);
        idle();
        assertThat(view("warehouse-3").phase()).isEqualTo("READY");
        assertThat(docker.apiCount("warehouse-3")).isEqualTo(2);
    }

    /** 20 clients scale the same warehouse at once: it ends consistent, with the last value applied. */
    @Test
    void concurrentRequestsConvergeToOneConsistentState() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < 20; c++) {
                clients.submit(() -> {
                    start.await();
                    return manager.scale("warehouse-6", ThreadLocalRandom.current().nextInt(1, 6));
                });
            }
            start.countDown();
        }
        idle();
        int desired = view("warehouse-6").desiredReplicas();
        assertThat(docker.apiCount("warehouse-6")).isEqualTo(desired);
        assertThat(docker.nodeCount("warehouse-6")).isEqualTo(Math.max(3, desired));
        assertThat(registry.inWarehouse("warehouse-6")).hasSize(desired)
                .extracting(Backend::name).doesNotHaveDuplicates();
        assertThat(mongo.sets.get("warehouse-6")).hasSize(Math.max(3, desired));
        assertThat(view("warehouse-6").phase()).isEqualTo("READY");
    }

    @Test
    void containersOfAnEarlierRunAreAdoptedAndInitialWarehousesCreated() throws Exception {
        manager.scale("warehouse-7", 2);
        idle();
        manager.close();

        BackendRegistry fresh = new BackendRegistry();
        WarehouseManager restarted = new WarehouseManager(fresh, docker, mongo,
                props(true, List.of("warehouse-1", "warehouse-7")));
        docker.log.clear();
        restarted.start();
        restarted.awaitIdle(Duration.ofSeconds(5));

        assertThat(fresh.inWarehouse("warehouse-7")).hasSize(2);              // adopted, nothing new started for it
        assertThat(docker.log).noneMatch(l -> l.contains("warehouse-7"));
        assertThat(docker.apiCount("warehouse-1")).isEqualTo(1);               // initial warehouse created
        assertThat(docker.nodeCount("warehouse-1")).isEqualTo(3);
        restarted.close();
    }

    @Test
    void monitorShowsWhichNodeIsPrimary() throws Exception {
        manager.scale("warehouse-2", 3);
        idle();
        manager.pollStatus();
        assertThat(view("warehouse-2").dbNodes()).extracting(WarehouseManager.DbNodeView::state)
                .containsExactly("PRIMARY", "SECONDARY", "SECONDARY");
    }

    // ---------- helpers

    private void idle() throws InterruptedException {
        assertThat(manager.awaitIdle(Duration.ofSeconds(10))).as("reconcile finished").isTrue();
    }

    private WarehouseView view(String w) {
        return manager.view().stream().filter(v -> v.name().equals(w)).findFirst().orElseThrow();
    }

    private static int status(Runnable call) {
        try {
            call.run();
        } catch (ScalingException e) {
            return e.status();
        }
        throw new AssertionError("expected a ScalingException");
    }

    private static GatewayProperties props(boolean enabled, List<String> initial) {
        var provisioner = new GatewayProperties.Provisioner(enabled, "/var/run/docker.sock", "gamevault-api:latest",
                "mongo:8", "gamevault-net", 8080, 8081, 8099, "0.25", "redis", 6379,
                3, 5, 5, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5));
        return new GatewayProperties(List.of(), GatewayProperties.Strategy.ROUND_ROBIN, "warehouse-1",
                new GatewayProperties.Health(Duration.ofSeconds(2), Duration.ofSeconds(2), 2),
                new GatewayProperties.Pool(10, 5, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new GatewayProperties.Cache(false, Duration.ofSeconds(1), List.of("/api/"), 1024),
                provisioner, initial);
    }

    private static void pause() {
        try {
            Thread.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
