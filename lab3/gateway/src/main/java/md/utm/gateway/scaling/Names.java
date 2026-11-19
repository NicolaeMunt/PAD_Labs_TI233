package md.utm.gateway.scaling;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Naming rules shared by the gateway, the Docker provisioner and the replica-set manager.
 *
 * A warehouse {@code w} with {@code n} API instances has:
 * <ul>
 *   <li>API containers {@code api-w-1 .. api-w-n}</li>
 *   <li>its own replica set {@code rs-w} with {@code max(minDbCopies, n)} Mongo nodes
 *       {@code mongo-w-1 ..}, each with its own volume {@code gamevault-mongo-w-i}</li>
 *   <li>database {@code gamevault_w}</li>
 * </ul>
 * API instance i prefers reading from Mongo node i (member tag {@code node: "i"}); writes always
 * go to the replica set's primary.
 */
public final class Names {

    public static final String LABEL_MANAGED = "gamevault.managed";      // "true" on everything the gateway creates
    public static final String LABEL_ROLE = "gamevault.role";            // ROLE_API or ROLE_MONGO
    public static final String LABEL_WAREHOUSE = "gamevault.warehouse";
    public static final String LABEL_INDEX = "gamevault.index";          // 1-based
    public static final String LABEL_HOST_PORT = "gamevault.host-port";  // API only: published port on the host
    public static final String ROLE_API = "api";
    public static final String ROLE_MONGO = "mongo";

    public static final int MONGO_PORT = 27017;
    /** member tag used for "API i reads from node i" */
    public static final String NODE_TAG = "node";

    private Names() { }

    public static String apiContainer(String warehouse, int index)   { return "api-" + warehouse + "-" + index; }
    public static String mongoContainer(String warehouse, int index) { return "mongo-" + warehouse + "-" + index; }
    public static String mongoVolume(String warehouse, int index)    { return "gamevault-mongo-" + warehouse + "-" + index; }
    public static String mongoHost(String warehouse, int index)      { return mongoContainer(warehouse, index) + ":" + MONGO_PORT; }
    public static String replicaSet(String warehouse)                { return "rs-" + warehouse; }
    public static String database(String warehouse)                  { return "gamevault_" + warehouse; }

    /** Database copies for a number of API instances: equal to it, but at least {@code min} (0 stays 0). */
    public static int dbCopies(int apiReplicas, int min) {
        return apiReplicas <= 0 ? 0 : Math.max(min, apiReplicas);
    }

    /**
     * Connection string for API instance {@code apiIndex}: every node as a seed, writes acknowledged
     * by a majority, reads from the nearest member tagged {@code node:apiIndex}, falling back to any
     * member (the empty tag set) while that node is not readable.
     */
    public static String mongoUri(String warehouse, Collection<String> hosts, int apiIndex) {
        return "mongodb://" + hosts.stream().sorted().collect(Collectors.joining(","))
                + "/?replicaSet=" + replicaSet(warehouse)
                + "&w=majority&retryWrites=true"
                + "&readPreference=nearest&readPreferenceTags=" + NODE_TAG + ":" + apiIndex + "&readPreferenceTags=";
    }
}
