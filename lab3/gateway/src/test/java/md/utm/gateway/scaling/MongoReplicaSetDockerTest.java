package md.utm.gateway.scaling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import md.utm.gateway.scaling.ReplicaSet.Member;
import md.utm.gateway.scaling.ReplicaSet.MemberState;
import md.utm.gateway.scaling.ReplicaSet.Status;

/**
 * MongoReplicaSet against a real replica set of 4 throwaway mongod containers (opt-in):
 *
 * <pre>mvn -f gateway/pom.xml test -Dtest=MongoReplicaSetDockerTest -Dgv.mongo.it=true -Dgv.mongo.it.host=&lt;host IP&gt;</pre>
 *
 * Members are named {@code <host>:2720N}, which must resolve to the host both from this JVM and
 * from inside the containers (the host's LAN IP works; host.docker.internal does on some setups).
 * The containers gvtest-mongo-1..4 are removed afterwards, also when a test fails.
 */
@EnabledIfSystemProperty(named = "gv.mongo.it", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoReplicaSetDockerTest {

    private static final String WAREHOUSE = "gvtest";                 // replica set rs-gvtest
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final String HOST = System.getProperty("gv.mongo.it.host", "host.docker.internal");

    private static final MongoReplicaSet rs = new MongoReplicaSet();
    private static String removedPrimary;

    static String host(int i) {
        return HOST + ":" + (27200 + i);
    }

    @BeforeAll
    static void startContainers() throws Exception {
        removeContainers();
        for (int i = 1; i <= 4; i++) {
            docker("run", "-d", "--name", "gvtest-mongo-" + i, "-p", (27200 + i) + ":27017", "mongo:8",
                    "mongod", "--replSet", Names.replicaSet(WAREHOUSE), "--bind_ip_all", "--wiredTigerCacheSizeGB", "0.25");
        }
        for (int i = 1; i <= 4; i++) rs.awaitReachable(host(i), TIMEOUT);
    }

    @AfterAll
    static void stopContainers() throws Exception {
        removeContainers();
    }

    @Test
    @Order(1)
    void initiateElectsAPrimaryAndIsIdempotent() throws IOException {
        Map<Integer, String> members = Map.of(1, host(1), 2, host(2), 3, host(3));
        rs.initiate(WAREHOUSE, members, TIMEOUT);
        rs.initiate(WAREHOUSE, members, TIMEOUT);                    // already initiated: no-op

        Status s = rs.status(WAREHOUSE, List.of(host(1), host(2), host(3)));
        assertThat(s.name()).isEqualTo("rs-gvtest");
        assertThat(s.primary()).isIn(host(1), host(2), host(3));
        assertThat(s.members()).extracting(Member::index).containsExactly(1, 2, 3);
        assertThat(s.members()).extracting(Member::host).containsExactly(host(1), host(2), host(3));
        assertThat(s.members()).filteredOn(m -> m.state() == MemberState.PRIMARY).hasSize(1);
    }

    @Test
    @Order(2)
    void addMemberAddsTheFourthNode() throws Exception {
        List<String> seeds = List.of(host(1), host(2), host(3));
        rs.addMember(WAREHOUSE, seeds, 4, host(4), TIMEOUT);
        rs.addMember(WAREHOUSE, seeds, 4, host(4), TIMEOUT);          // already a member: no-op

        Status s = awaitStatus(seeds, st -> st.members().stream()
                .anyMatch(m -> m.host().equals(host(4)) && m.state() == MemberState.SECONDARY));
        assertThat(s.members()).extracting(Member::index).containsExactly(1, 2, 3, 4);
    }

    @Test
    @Order(3)
    void removingThePrimaryElectsAnotherOneFirst() throws Exception {
        List<String> all = List.of(host(1), host(2), host(3), host(4));
        Status before = rs.status(WAREHOUSE, all);
        removedPrimary = before.primary();
        assertThat(removedPrimary).isNotNull();

        rs.removeMember(WAREHOUSE, all, removedPrimary, TIMEOUT);
        rs.removeMember(WAREHOUSE, all, removedPrimary, TIMEOUT);     // already gone: no-op

        List<String> rest = new ArrayList<>(all);
        rest.remove(removedPrimary);
        Status after = awaitStatus(rest, st -> st.primary() != null);
        assertThat(after.primary()).isNotEqualTo(removedPrimary);
        assertThat(after.members()).hasSize(3).extracting(Member::host).doesNotContain(removedPrimary);
    }

    @Test
    @Order(4)
    void statusFailsWhenNoSeedAnswers() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rs.status(WAREHOUSE, List.of(HOST + ":27299")))
                .isInstanceOf(IOException.class);
    }

    private static Status awaitStatus(List<String> seeds, java.util.function.Predicate<Status> ok) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        Status last = null;
        while (System.nanoTime() < deadline) {
            try {
                last = rs.status(WAREHOUSE, seeds);
                if (ok.test(last)) return last;
            } catch (IOException e) {
                // election in progress
            }
            Thread.sleep(500);
        }
        throw new AssertionError("condition not reached; last status: " + last);
    }

    private static void removeContainers() throws Exception {
        for (int i = 1; i <= 4; i++) {
            new ProcessBuilder("docker", "rm", "-f", "gvtest-mongo-" + i).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
        }
    }

    private static void docker(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
    }
}
