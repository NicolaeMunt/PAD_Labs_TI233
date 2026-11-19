package md.utm.gateway.scaling;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.ClusterConnectionMode;

/**
 * {@link ReplicaSet} with the MongoDB Java driver.
 *
 * Every command goes to one node over a <b>direct</b> connection (no replica-set discovery),
 * opened for the call and closed after it, with short connect / server-selection timeouts: a
 * dead node fails a call within seconds instead of hanging it. The class keeps no state, so
 * calls for different warehouses (and status polls next to a reconfig) can run concurrently.
 *
 * Reconfigs go to the primary, change one member at a time (MongoDB allows one voting member
 * change per reconfig) and are retried while the set is busy (election, previous reconfig not
 * yet committed).
 */
public class MongoReplicaSet implements ReplicaSet, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MongoReplicaSet.class);

    private static final int CONNECT_MS = 3_000;
    private static final int SELECT_MS = 3_000;
    /** status / hello / ping */
    private static final int READ_MS = 10_000;
    /** replSetInitiate / replSetReconfig / replSetStepDown may wait for other members */
    private static final int ADMIN_READ_MS = 60_000;
    private static final long RETRY_MS = 500;

    private static final int NOT_YET_INITIALIZED = 94;
    /** error codes that mean "the set is busy, try again shortly" */
    private static final Set<Integer> TRANSIENT = Set.of(
            6,      // HostUnreachable
            7,      // HostNotFound
            89,     // NetworkTimeout
            91,     // ShutdownInProgress
            109,    // ConfigurationInProgress
            117,    // ConflictingOperationInProgress
            133,    // FailedToSatisfyReadPreference
            189,    // PrimarySteppedDown
            262,    // ExceededTimeLimit
            10107,  // NotWritablePrimary
            11600,  // InterruptedAtShutdown
            11602,  // InterruptedDueToReplStateChange
            13435,  // NotPrimaryNoSecondaryOk
            13436); // NotPrimaryOrSecondary

    public MongoReplicaSet() { }

    // ------------------------------------------------------------------ ReplicaSet

    @Override
    public void awaitReachable(String host, Duration timeout) throws IOException {
        long deadline = deadline(timeout);
        MongoException last = null;
        while (true) {
            try {
                command(host, new Document("ping", 1), READ_MS);
                return;
            } catch (MongoException e) {
                last = e;
            }
            if (expired(deadline)) {
                throw new IOException("Mongo node " + host + " not reachable within " + timeout + ": " + describe(last));
            }
            pause();
        }
    }

    @Override
    public void initiate(String warehouse, Map<Integer, String> members, Duration timeout) throws IOException {
        if (members.isEmpty()) throw new IllegalArgumentException("a replica set needs at least one member");
        long deadline = deadline(timeout);
        String setName = Names.replicaSet(warehouse);
        String first = new TreeMap<>(members).firstEntry().getValue();

        while (true) {
            try {
                command(first, new Document("replSetGetStatus", 1), READ_MS);
                log.info("Replica set {} is already initiated", setName);
                break;
            } catch (MongoCommandException e) {
                if (e.getErrorCode() != NOT_YET_INITIALIZED) {
                    if (retryable(e) && !expired(deadline)) { pause(); continue; }
                    throw new IOException("Could not read the status of " + setName + " on " + first + ": " + describe(e), e);
                }
                try {
                    command(first, new Document("replSetInitiate", initiateConfig(setName, members)), ADMIN_READ_MS);
                    log.info("Initiated replica set {} with {}", setName, new TreeMap<>(members).values());
                    break;
                } catch (MongoException ie) {
                    // another caller may have initiated it in between: the next round sees that
                    if (expired(deadline)) throw new IOException("Could not initiate " + setName + ": " + describe(ie), ie);
                    pause();
                }
            } catch (MongoException e) {
                if (expired(deadline)) throw new IOException("Mongo node " + first + " not reachable: " + describe(e), e);
                pause();
            }
        }
        waitForPrimary(setName, members.values(), null, deadline, timeout);
    }

    @Override
    public void addMember(String warehouse, Collection<String> seeds, int index, String host, Duration timeout)
            throws IOException {
        String setName = Names.replicaSet(warehouse);
        long deadline = deadline(timeout);
        Set<String> hosts = withHost(seeds, host);
        Exception last = null;
        while (true) {
            try {
                String primary = waitForPrimary(setName, hosts, null, deadline, timeout);
                Document config = getConfig(primary);
                Document changed = withMember(config, index, host);
                if (changed == null) {
                    log.info("{} is already a member of {}", host, setName);
                    return;
                }
                command(primary, new Document("replSetReconfig", changed), ADMIN_READ_MS);
                log.info("Added {} to {} (config version {})", host, setName, changed.get("version"));
                return;
            } catch (MongoException e) {
                if (!retryable(e)) throw new IOException("Could not add " + host + " to " + setName + ": " + describe(e), e);
                last = e;
            }
            if (expired(deadline)) {
                throw new IOException("Could not add " + host + " to " + setName + " within " + timeout + ": " + describe(last));
            }
            pause();
        }
    }

    @Override
    public void removeMember(String warehouse, Collection<String> seeds, String host, Duration timeout)
            throws IOException {
        String setName = Names.replicaSet(warehouse);
        long deadline = deadline(timeout);
        Set<String> hosts = withHost(seeds, host);
        Exception last = null;
        while (true) {
            try {
                String primary = waitForPrimary(setName, hosts, null, deadline, timeout);
                if (primary.equals(host)) {
                    stepDown(host);
                    // the old primary will not stand for election for 60 s: wait for another one
                    primary = waitForPrimary(setName, hosts, host, deadline, timeout);
                }
                Document config = getConfig(primary);
                Document changed = withoutMember(config, host);
                if (changed == null) {
                    log.info("{} is not a member of {}", host, setName);
                    return;
                }
                command(primary, new Document("replSetReconfig", changed), ADMIN_READ_MS);
                log.info("Removed {} from {} (config version {})", host, setName, changed.get("version"));
                return;
            } catch (MongoException e) {
                if (!retryable(e)) throw new IOException("Could not remove " + host + " from " + setName + ": " + describe(e), e);
                last = e;
            }
            if (expired(deadline)) {
                throw new IOException("Could not remove " + host + " from " + setName + " within " + timeout + ": " + describe(last));
            }
            pause();
        }
    }

    @Override
    public Status status(String warehouse, Collection<String> seeds) throws IOException {
        String setName = Names.replicaSet(warehouse);
        MongoException last = null;
        for (String seed : new LinkedHashSet<>(seeds)) {
            try {
                Document status = command(seed, new Document("replSetGetStatus", 1), READ_MS);
                Document config = null;
                try {
                    config = getConfig(seed);
                } catch (MongoException e) {
                    // indexes then come from the member _id
                }
                return toStatus(status, config);
            } catch (MongoException e) {
                last = e;
            }
        }
        throw new IOException("No node of " + setName + " answered (" + seeds + "): " + describe(last));
    }

    @Override
    public void close() {
        // nothing to release: clients are opened per call
    }

    // ------------------------------------------------------------------ driver calls

    /** Runs an admin command on one node over a direct connection. */
    Document command(String host, Document command, int readTimeoutMs) {
        try (MongoClient client = directClient(host, readTimeoutMs)) {
            return client.getDatabase("admin").runCommand(command);
        }
    }

    private static MongoClient directClient(String host, int readTimeoutMs) {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyToClusterSettings(b -> b
                        .hosts(List.of(new ServerAddress(host)))
                        .mode(ClusterConnectionMode.SINGLE)
                        .serverSelectionTimeout(SELECT_MS, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(b -> b
                        .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
                        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(b -> b.maxSize(1))
                .build();
        return MongoClients.create(settings);
    }

    private Document getConfig(String host) {
        Document reply = command(host, new Document("replSetGetConfig", 1), READ_MS);
        return reply.get("config", Document.class);
    }

    /** Asks the primary to step down; it drops connections while doing so, which is expected. */
    private void stepDown(String host) {
        try {
            command(host, new Document("replSetStepDown", 60).append("secondaryCatchUpPeriodSecs", 15), ADMIN_READ_MS);
            log.info("Primary {} stepped down", host);
        } catch (MongoSocketException e) {
            log.info("Primary {} closed the connection while stepping down (expected)", host);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() == 10107) return;                     // not primary any more
            throw e;
        }
    }

    /**
     * Waits until some node reports a primary (other than {@code notHost}) and returns its host.
     * Asks each known host with {@code hello}; a node that knows the primary names it.
     */
    private String waitForPrimary(String setName, Collection<String> hosts, String notHost, long deadline, Duration timeout)
            throws IOException {
        while (true) {
            for (String h : hosts) {
                try {
                    Document hello = command(h, new Document("hello", 1), READ_MS);
                    String primary = hello.getString("primary");
                    if (primary == null && Boolean.TRUE.equals(hello.getBoolean("isWritablePrimary"))) primary = h;
                    if (primary != null && !primary.equals(notHost)) return primary;
                } catch (MongoException e) {
                    // dead or busy node: ask the next one
                }
            }
            if (expired(deadline)) {
                throw new IOException(setName + " has no primary" + (notHost == null ? "" : " other than " + notHost)
                        + " after " + timeout);
            }
            pause();
        }
    }

    // ------------------------------------------------------------------ pure helpers (unit-tested)

    static Document member(int index, String host) {
        return new Document("_id", index)
                .append("host", host)
                .append("priority", 1)
                .append("votes", 1)
                .append("tags", new Document(Names.NODE_TAG, String.valueOf(index)));
    }

    static Document initiateConfig(String setName, Map<Integer, String> members) {
        List<Document> list = new ArrayList<>();
        new TreeMap<>(members).forEach((index, host) -> list.add(member(index, host)));
        return new Document("_id", setName).append("members", list);
    }

    /** A copy of {@code config} with the member added and the version bumped; null if the host is already in it. */
    static Document withMember(Document config, int index, String host) {
        List<Document> members = members(config);
        if (members.stream().anyMatch(m -> host.equals(m.getString("host")))) return null;
        Set<Integer> ids = new java.util.HashSet<>();
        members.forEach(m -> ids.add(((Number) m.get("_id")).intValue()));
        int id = ids.contains(index) ? ids.stream().max(Integer::compare).orElse(0) + 1 : index;

        Document m = member(index, host).append("_id", id);
        List<Document> changed = new ArrayList<>(members);
        changed.add(m);
        return nextVersion(config, changed);
    }

    /** A copy of {@code config} without the member at {@code host}; null if it is not in it. */
    static Document withoutMember(Document config, String host) {
        List<Document> members = members(config);
        List<Document> changed = members.stream().filter(m -> !host.equals(m.getString("host"))).toList();
        if (changed.size() == members.size()) return null;
        return nextVersion(config, changed);
    }

    static MemberState state(String stateStr) {
        if (stateStr == null) return MemberState.UNKNOWN;
        try {
            return MemberState.valueOf(stateStr.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemberState.UNKNOWN;                                // e.g. "(not reachable/healthy)"
        }
    }

    /** Index of a config member: its node tag, else its _id. */
    static int index(Document member) {
        Document tags = member.get("tags", Document.class);
        if (tags != null && tags.get(Names.NODE_TAG) != null) {
            try {
                return Integer.parseInt(String.valueOf(tags.get(Names.NODE_TAG)));
            } catch (NumberFormatException e) {
                // fall through to _id
            }
        }
        return ((Number) member.get("_id")).intValue();
    }

    /** Turns replSetGetStatus (and, when available, the config for the node tags) into a Status. */
    static Status toStatus(Document status, Document config) {
        Map<String, Integer> indexByHost = new HashMap<>();
        if (config != null) members(config).forEach(m -> indexByHost.put(m.getString("host"), index(m)));

        List<Member> members = new ArrayList<>();
        String primary = null;
        for (Document m : status.getList("members", Document.class, List.of())) {
            String host = m.getString("name");
            MemberState state = state(m.getString("stateStr"));
            Number health = (Number) m.get("health");
            int index = indexByHost.getOrDefault(host, ((Number) m.get("_id")).intValue());
            members.add(new Member(index, host, state, health != null && health.doubleValue() == 1.0));
            if (state == MemberState.PRIMARY) primary = host;
        }
        members.sort(Comparator.comparingInt(Member::index));
        return new Status(status.getString("set"), List.copyOf(members), primary);
    }

    private static List<Document> members(Document config) {
        return config.getList("members", Document.class, List.of());
    }

    private static Document nextVersion(Document config, List<Document> members) {
        Document copy = new Document(config);
        copy.remove("term");                                           // set by the server
        copy.put("version", ((Number) config.get("version")).intValue() + 1);
        copy.put("members", members);
        return copy;
    }

    // ------------------------------------------------------------------ small things

    static boolean retryable(MongoException e) {
        if (e instanceof MongoSocketException || e instanceof MongoTimeoutException) return true;
        if (e instanceof MongoCommandException c) return TRANSIENT.contains(c.getErrorCode());
        return TRANSIENT.contains(e.getCode());
    }

    private static Set<String> withHost(Collection<String> seeds, String host) {
        Set<String> hosts = new LinkedHashSet<>(seeds);
        hosts.add(host);
        return hosts;
    }

    private static String describe(Exception e) {
        if (e == null) return "no attempt";
        if (e instanceof MongoCommandException c) return c.getErrorCodeName() + " (" + c.getErrorCode() + "): " + c.getErrorMessage();
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static long deadline(Duration timeout) {
        return System.nanoTime() + timeout.toNanos();
    }

    private static boolean expired(long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(RETRY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for the replica set");
        }
    }
}
