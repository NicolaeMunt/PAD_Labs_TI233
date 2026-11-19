package md.utm.gateway.scaling;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSocketException;
import com.mongodb.ServerAddress;

import md.utm.gateway.scaling.ReplicaSet.Member;
import md.utm.gateway.scaling.ReplicaSet.MemberState;
import md.utm.gateway.scaling.ReplicaSet.Status;

/** The pure parts of MongoReplicaSet: config documents and status mapping (no MongoDB needed). */
class MongoReplicaSetTest {

    @Test
    void initiateConfigHasOneTaggedVotingMemberPerIndexInOrder() {
        Document config = MongoReplicaSet.initiateConfig("rs-warehouse-2", Map.of(
                2, "mongo-warehouse-2-2:27017", 1, "mongo-warehouse-2-1:27017", 3, "mongo-warehouse-2-3:27017"));

        assertThat(config.getString("_id")).isEqualTo("rs-warehouse-2");
        List<Document> members = config.getList("members", Document.class);
        assertThat(members).extracting(m -> m.getInteger("_id")).containsExactly(1, 2, 3);
        assertThat(members).extracting(m -> m.getString("host"))
                .containsExactly("mongo-warehouse-2-1:27017", "mongo-warehouse-2-2:27017", "mongo-warehouse-2-3:27017");
        assertThat(members).allSatisfy(m -> {
            assertThat(m.getInteger("votes")).isEqualTo(1);
            assertThat(m.getInteger("priority")).isEqualTo(1);
            assertThat(m.get("tags", Document.class).getString(Names.NODE_TAG)).isEqualTo(String.valueOf(m.getInteger("_id")));
        });
    }

    @Test
    void withMemberAddsTaggedMemberAndBumpsVersionWithoutTerm() {
        Document config = config(7, member(1, "a:27017"), member(2, "b:27017"), member(3, "c:27017"));
        config.put("term", 4L);

        Document changed = MongoReplicaSet.withMember(config, 4, "d:27017");

        assertThat(changed.getInteger("version")).isEqualTo(8);
        assertThat(changed.containsKey("term")).isFalse();
        Document added = changed.getList("members", Document.class).get(3);
        assertThat(added.getInteger("_id")).isEqualTo(4);
        assertThat(added.getString("host")).isEqualTo("d:27017");
        assertThat(added.get("tags", Document.class).getString("node")).isEqualTo("4");
        // the original is untouched
        assertThat(config.getList("members", Document.class)).hasSize(3);
        assertThat(config.getInteger("version")).isEqualTo(7);
    }

    @Test
    void withMemberIsNullWhenTheHostIsAlreadyAMember() {
        assertThat(MongoReplicaSet.withMember(config(3, member(1, "a:27017")), 1, "a:27017")).isNull();
    }

    @Test
    void withMemberPicksAFreeIdWhenTheIndexIdIsTaken() {
        // _id 2 is used by a member whose host is different (e.g. re-added node with another name)
        Document config = config(5, member(1, "a:27017"), member(2, "x:27017"), member(5, "e:27017"));
        Document added = MongoReplicaSet.withMember(config, 2, "b:27017").getList("members", Document.class).get(3);
        assertThat(added.getInteger("_id")).isEqualTo(6);
        assertThat(added.get("tags", Document.class).getString("node")).isEqualTo("2");   // index stays in the tag
    }

    @Test
    void withoutMemberRemovesItAndBumpsVersion() {
        Document config = config(2, member(1, "a:27017"), member(2, "b:27017"), member(3, "c:27017"));
        Document changed = MongoReplicaSet.withoutMember(config, "b:27017");
        assertThat(changed.getInteger("version")).isEqualTo(3);
        assertThat(changed.getList("members", Document.class)).extracting(m -> m.getString("host"))
                .containsExactly("a:27017", "c:27017");
        assertThat(MongoReplicaSet.withoutMember(config, "zzz:27017")).isNull();
    }

    @Test
    void stateStringsMapToMemberStates() {
        assertThat(MongoReplicaSet.state("PRIMARY")).isEqualTo(MemberState.PRIMARY);
        assertThat(MongoReplicaSet.state("SECONDARY")).isEqualTo(MemberState.SECONDARY);
        assertThat(MongoReplicaSet.state("STARTUP2")).isEqualTo(MemberState.STARTUP2);
        assertThat(MongoReplicaSet.state("(not reachable/healthy)")).isEqualTo(MemberState.UNKNOWN);
        assertThat(MongoReplicaSet.state(null)).isEqualTo(MemberState.UNKNOWN);
    }

    @Test
    void indexComesFromTheNodeTagThenTheId() {
        assertThat(MongoReplicaSet.index(member(3, "c:27017"))).isEqualTo(3);
        assertThat(MongoReplicaSet.index(new Document("_id", 6).append("host", "x").append("tags", new Document("node", "2"))))
                .isEqualTo(2);
        assertThat(MongoReplicaSet.index(new Document("_id", 4).append("host", "x"))).isEqualTo(4);
    }

    @Test
    void statusMapsMembersPrimaryAndHealth() {
        Document status = new Document("set", "rs-warehouse-2").append("members", List.of(
                new Document("_id", 6).append("name", "b:27017").append("health", 1.0).append("stateStr", "SECONDARY"),
                new Document("_id", 1).append("name", "a:27017").append("health", 1.0).append("stateStr", "PRIMARY"),
                new Document("_id", 3).append("name", "c:27017").append("health", 0.0).append("stateStr", "(not reachable/healthy)")));
        Document config = config(9, member(1, "a:27017"),
                new Document("_id", 6).append("host", "b:27017").append("tags", new Document("node", "2")),
                member(3, "c:27017"));

        Status s = MongoReplicaSet.toStatus(status, config);

        assertThat(s.name()).isEqualTo("rs-warehouse-2");
        assertThat(s.primary()).isEqualTo("a:27017");
        assertThat(s.members()).containsExactly(
                new Member(1, "a:27017", MemberState.PRIMARY, true),
                new Member(2, "b:27017", MemberState.SECONDARY, true),
                new Member(3, "c:27017", MemberState.UNKNOWN, false));
    }

    @Test
    void statusWithoutConfigUsesIdsAndHasNoPrimaryDuringAnElection() {
        Document status = new Document("set", "rs-w").append("members", List.of(
                new Document("_id", 2).append("name", "b:27017").append("health", 1).append("stateStr", "SECONDARY"),
                new Document("_id", 1).append("name", "a:27017").append("health", 1).append("stateStr", "SECONDARY")));
        Status s = MongoReplicaSet.toStatus(status, null);
        assertThat(s.primary()).isNull();
        assertThat(s.members()).extracting(Member::index).containsExactly(1, 2);
    }

    @Test
    void electionAndNetworkErrorsAreRetriedOthersAreNot() {
        assertThat(MongoReplicaSet.retryable(commandError(109, "ConfigurationInProgress"))).isTrue();
        assertThat(MongoReplicaSet.retryable(commandError(10107, "NotWritablePrimary"))).isTrue();
        assertThat(MongoReplicaSet.retryable(new MongoSocketException("reset", new ServerAddress("a")))).isTrue();
        assertThat(MongoReplicaSet.retryable(commandError(93, "InvalidReplicaSetConfig"))).isFalse();
    }

    private static Document member(int index, String host) {
        return MongoReplicaSet.member(index, host);
    }

    private static Document config(int version, Document... members) {
        return new Document("_id", "rs-w").append("version", version).append("members", List.of(members));
    }

    private static MongoCommandException commandError(int code, String name) {
        return new MongoCommandException(new org.bson.BsonDocument("ok", new org.bson.BsonDouble(0))
                .append("code", new org.bson.BsonInt32(code))
                .append("codeName", new org.bson.BsonString(name))
                .append("errmsg", new org.bson.BsonString(name)), new ServerAddress("a"));
    }
}
