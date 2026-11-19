package md.utm.gateway.scaling;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Starts and stops the containers of a warehouse: API instances and Mongo nodes.
 * Names, labels and volumes follow {@link Names}. The Docker implementation creates
 * containers; a Kubernetes one (Task 6) would create pods.
 */
public interface InstanceProvisioner {

    /**
     * A running API instance.
     *
     * @param id       container id
     * @param name     container name = APP_INSTANCE_ID = host name, e.g. api-warehouse-2-1
     * @param baseUri  how the gateway reaches it, e.g. http://api-warehouse-2-1:8080
     * @param hostPort port published on the host for direct calls (e.g. 8081), or null if none
     */
    record Instance(String id, String name, String warehouse, int index, URI baseUri, Integer hostPort) { }

    /**
     * A Mongo node of a warehouse's replica set.
     *
     * @param host host:port inside the Docker network, e.g. mongo-warehouse-2-1:27017
     */
    record DbNode(String id, String name, String warehouse, int index, String host) { }

    /** Everything the provisioner created earlier (also by a previous run of the gateway). */
    record Inventory(List<Instance> apis, List<DbNode> dbNodes) { }

    boolean isEnabled();

    /**
     * Starts API instance {@code index} of the warehouse, connected to {@code mongoUri}, and
     * publishes it on a free host port. Returns once the container runs (not once it is ready).
     */
    Instance startApi(String warehouse, int index, String mongoUri) throws IOException;

    /** Stops and removes an API container. Does nothing if it is already gone. */
    void stopApi(String containerId) throws IOException;

    /**
     * Starts Mongo node {@code index} of the warehouse's replica set {@code rs-<warehouse>}, with its
     * own (new or existing) data volume. Returns once the container runs; the replica set is
     * configured separately ({@link ReplicaSet}).
     */
    DbNode startDbNode(String warehouse, int index) throws IOException;

    /** Stops and removes the Mongo container and deletes its data volume. Does nothing if gone. */
    void removeDbNode(DbNode node) throws IOException;

    Inventory discover() throws IOException;

    /** Used when gateway.provisioner.enabled=false. */
    InstanceProvisioner DISABLED = new InstanceProvisioner() {
        public boolean isEnabled()                                { return false; }
        public Instance startApi(String w, int i, String uri)     { throw new UnsupportedOperationException("provisioning is disabled"); }
        public void stopApi(String id)                            { }
        public DbNode startDbNode(String w, int i)                { throw new UnsupportedOperationException("provisioning is disabled"); }
        public void removeDbNode(DbNode node)                     { }
        public Inventory discover()                               { return new Inventory(List.of(), List.of()); }
    };
}
