package md.utm.gateway.scaling;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Manages one warehouse's MongoDB replica set {@code rs-<warehouse>} (see {@link Names}).
 * Every call gets the seed hosts to connect to (host:port inside the Docker network), because
 * the gateway is the one that knows which nodes exist.
 *
 * Member {@code i} always has {@code _id = i}, host {@link Names#mongoHost} and the tag
 * {@code {node: "i"}}; it votes and can become primary.
 */
public interface ReplicaSet {

    enum MemberState { PRIMARY, SECONDARY, STARTUP, STARTUP2, RECOVERING, ROLLBACK, ARBITER, DOWN, REMOVED, UNKNOWN }

    record Member(int index, String host, MemberState state, boolean healthy) { }

    /** @param primary host of the primary, or null while there is none (election, or not initiated) */
    record Status(String name, List<Member> members, String primary) { }

    /** Waits until the mongod at {@code host} answers a ping. */
    void awaitReachable(String host, Duration timeout) throws IOException;

    /**
     * Initiates the replica set with these members (index → host). Does nothing if it is already
     * initiated. Waits until a primary is elected.
     */
    void initiate(String warehouse, Map<Integer, String> members, Duration timeout) throws IOException;

    /** Adds member {@code index} at {@code host}; does nothing if a member with that host exists. */
    void addMember(String warehouse, Collection<String> seeds, int index, String host, Duration timeout) throws IOException;

    /**
     * Removes the member at {@code host}; if it is the primary it steps down first and the call
     * waits for the new primary. Does nothing if there is no such member.
     */
    void removeMember(String warehouse, Collection<String> seeds, String host, Duration timeout) throws IOException;

    Status status(String warehouse, Collection<String> seeds) throws IOException;
}
