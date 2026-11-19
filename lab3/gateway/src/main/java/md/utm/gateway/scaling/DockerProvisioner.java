package md.utm.gateway.scaling;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import md.utm.gateway.config.GatewayProperties;
import tools.jackson.databind.JsonNode;

/**
 * Starts the containers of a warehouse through the Docker Engine API (the socket mounted into
 * the gateway): API instances from the image docker compose built, and the Mongo nodes of the
 * warehouse's own replica set, each with its own data volume.
 *
 * Every container and volume is labelled ({@link Names}: gamevault.managed / role / warehouse /
 * index, plus host-port on API instances), so the gateway finds them again after a restart.
 * docker compose does not know these containers: remove them by the label gamevault.managed=true.
 *
 * API instances are published on the lowest free host port of [hostPortFrom, hostPortTo], so each
 * one can also be called directly (http://localhost:&lt;port&gt;). Mongo nodes are not published.
 */
public class DockerProvisioner implements InstanceProvisioner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DockerProvisioner.class);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);

    private final GatewayProperties.Provisioner cfg;
    private final DockerEngineClient docker;

    public DockerProvisioner(GatewayProperties.Provisioner cfg) {
        this.cfg = cfg;
        this.docker = new DockerEngineClient(Path.of(cfg.dockerSocket()), cfg.dockerTimeout());
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // ---------------------------------------------------------------- API instances

    @Override
    public Instance startApi(String warehouse, int index, String mongoUri) throws IOException {
        String name = Names.apiContainer(warehouse, index);
        Set<Integer> used = usedHostPorts();
        String portKey = cfg.apiPort() + "/tcp";

        for (int port = cfg.hostPortFrom(); port <= cfg.hostPortTo(); port++) {
            if (used.contains(port)) continue;

            Map<String, String> labels = labels(Names.ROLE_API, warehouse, index);
            labels.put(Names.LABEL_HOST_PORT, String.valueOf(port));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Image", cfg.apiImage());
            body.put("Env", List.of(
                    "APP_INSTANCE_ID=" + name,
                    "APP_WAREHOUSE=" + warehouse,
                    "SPRING_MONGODB_URI=" + mongoUri,
                    "SPRING_DATA_REDIS_HOST=" + cfg.redisHost(),
                    "SPRING_DATA_REDIS_PORT=" + cfg.redisPort()));
            body.put("Labels", labels);
            body.put("ExposedPorts", Map.of(portKey, Map.of()));
            body.put("HostConfig", Map.of(
                    "NetworkMode", cfg.network(),
                    "RestartPolicy", Map.of("Name", "unless-stopped"),
                    "PortBindings", Map.of(portKey, List.of(Map.of("HostPort", String.valueOf(port))))));

            DockerEngineClient.Response created = create(name, body);
            if (created.status() == 404) {
                throw new IOException("Docker image " + cfg.apiImage() + " not found; build it with 'docker compose build'");
            }
            expectOk(created, "create container " + name);
            String id = docker.parseJson(created.body()).path("Id").asString();

            DockerEngineClient.Response started = docker.call("POST", "/containers/" + id + "/start", null);
            if (started.ok() || started.status() == 304) {
                log.info("Started API {} of {} on host port {}", name, warehouse, port);
                return new Instance(id, name, warehouse, index, apiUri(name), port);
            }
            docker.call("DELETE", "/containers/" + id + "?force=true", null);
            if (isPortConflict(started.body())) {
                log.info("Host port {} is taken, trying the next one for {}", port, name);
                used.add(port);
                continue;
            }
            expectOk(started, "start container " + name);
        }
        throw new IOException("No free host port in " + cfg.hostPortFrom() + "-" + cfg.hostPortTo() + " for " + name);
    }

    @Override
    public void stopApi(String containerId) throws IOException {
        stopAndRemove(containerId);
    }

    // ---------------------------------------------------------------- Mongo nodes

    @Override
    public DbNode startDbNode(String warehouse, int index) throws IOException {
        String name = Names.mongoContainer(warehouse, index);
        String volume = Names.mongoVolume(warehouse, index);

        Map<String, Object> volumeBody = new LinkedHashMap<>();
        volumeBody.put("Name", volume);
        volumeBody.put("Labels", labels(Names.ROLE_MONGO, warehouse, index));
        expectOk(docker.call("POST", "/volumes/create", volumeBody), "create volume " + volume);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Image", cfg.mongoImage());
        body.put("Cmd", List.of("mongod", "--replSet", Names.replicaSet(warehouse), "--bind_ip_all",
                "--wiredTigerCacheSizeGB", cfg.mongoCacheGb()));
        body.put("Labels", labels(Names.ROLE_MONGO, warehouse, index));
        body.put("HostConfig", Map.of(
                "Binds", List.of(volume + ":/data/db"),
                "NetworkMode", cfg.network(),
                "RestartPolicy", Map.of("Name", "unless-stopped")));

        String id;
        DockerEngineClient.Response created = docker.call("POST", "/containers/create?name=" + encode(name), body);
        if (created.status() == 404) {
            pull(cfg.mongoImage());
            created = docker.call("POST", "/containers/create?name=" + encode(name), body);
            if (created.status() == 404) throw new IOException("Docker image " + cfg.mongoImage() + " not found after pulling it");
        }
        if (created.status() == 409) {
            // a node of an earlier run: keep it, its volume holds the warehouse's data
            JsonNode existing = ours(name);
            id = existing.path("Id").asString();
            log.info("Reusing existing Mongo container {}", name);
        } else {
            expectOk(created, "create container " + name);
            id = docker.parseJson(created.body()).path("Id").asString();
        }

        DockerEngineClient.Response started = docker.call("POST", "/containers/" + id + "/start", null);
        if (!started.ok() && started.status() != 304) {
            expectOk(started, "start container " + name);
        }
        log.info("Started Mongo node {} of {}", name, Names.replicaSet(warehouse));
        return new DbNode(id, name, warehouse, index, Names.mongoHost(warehouse, index));
    }

    @Override
    public void removeDbNode(DbNode node) throws IOException {
        stopAndRemove(node.id());
        String volume = Names.mongoVolume(node.warehouse(), node.index());
        DockerEngineClient.Response removed = docker.call("DELETE", "/volumes/" + encode(volume), null);
        if (removed.status() != 404) expectOk(removed, "remove volume " + volume);
        log.info("Removed Mongo node {} and its volume", node.name());
    }

    // ---------------------------------------------------------------- discovery

    @Override
    public Inventory discover() throws IOException {
        List<Instance> apis = new ArrayList<>();
        List<DbNode> nodes = new ArrayList<>();
        for (JsonNode c : listManaged()) {
            JsonNode labels = c.path("Labels");
            String role = labels.path(Names.LABEL_ROLE).asString();
            String warehouse = labels.path(Names.LABEL_WAREHOUSE).asString();
            Integer index = parseInt(labels.path(Names.LABEL_INDEX).asString());
            String name = c.path("Names").path(0).asString().replaceFirst("^/", "");
            String id = c.path("Id").asString();
            if (warehouse.isEmpty() || index == null || name.isEmpty() || id.isEmpty()) continue;

            if (Names.ROLE_API.equals(role)) {
                apis.add(new Instance(id, name, warehouse, index, apiUri(name),
                        parseInt(labels.path(Names.LABEL_HOST_PORT).asString())));
            } else if (Names.ROLE_MONGO.equals(role)) {
                nodes.add(new DbNode(id, name, warehouse, index, Names.mongoHost(warehouse, index)));
            }
        }
        return new Inventory(apis, nodes);
    }

    // ---------------------------------------------------------------- helpers

    /** Creates a container; if the name is taken by one of ours (left over), replaces it. */
    private DockerEngineClient.Response create(String name, Map<String, Object> body) throws IOException {
        DockerEngineClient.Response created = docker.call("POST", "/containers/create?name=" + encode(name), body);
        if (created.status() == 409) {
            ours(name);
            docker.call("DELETE", "/containers/" + encode(name) + "?force=true", null);
            log.info("Replaced left-over container {}", name);
            created = docker.call("POST", "/containers/create?name=" + encode(name), body);
        }
        return created;
    }

    /** Inspects a container by name; throws unless the gateway created it. */
    private JsonNode ours(String name) throws IOException {
        DockerEngineClient.Response inspected = docker.call("GET", "/containers/" + encode(name) + "/json", null);
        expectOk(inspected, "inspect container " + name);
        JsonNode c = docker.parseJson(inspected.body());
        if (!"true".equals(c.path("Config").path("Labels").path(Names.LABEL_MANAGED).asString())) {
            throw new IOException("A container named " + name + " already exists and was not created by the gateway");
        }
        return c;
    }

    private Set<Integer> usedHostPorts() throws IOException {
        Set<Integer> used = new HashSet<>();
        for (JsonNode c : listManaged()) {
            JsonNode labels = c.path("Labels");
            if (!Names.ROLE_API.equals(labels.path(Names.LABEL_ROLE).asString())) continue;
            Integer port = parseInt(labels.path(Names.LABEL_HOST_PORT).asString());
            if (port != null) used.add(port);
        }
        return used;
    }

    private JsonNode listManaged() throws IOException {
        String filters = "{\"label\":[\"" + Names.LABEL_MANAGED + "=true\"]}";
        DockerEngineClient.Response listed = docker.call("GET", "/containers/json?all=true&filters=" + encode(filters), null);
        expectOk(listed, "list containers");
        return docker.parseJson(listed.body());
    }

    private void stopAndRemove(String id) throws IOException {
        // stop first (SIGTERM, up to 10 s) so the process shuts down cleanly, then remove
        DockerEngineClient.Response stopped = docker.call("POST", "/containers/" + id + "/stop?t=10", null);
        if (stopped.status() == 404) return;
        DockerEngineClient.Response removed = docker.call("DELETE", "/containers/" + id + "?force=true", null);
        if (removed.status() == 404) return;
        expectOk(removed, "remove container " + id);
        log.info("Removed container {}", id);
    }

    /** Pulls an image; the answer is a stream of JSON progress lines, an error is one of them. */
    private void pull(String image) throws IOException {
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        String repo = colon > slash ? image.substring(0, colon) : image;
        String tag = colon > slash ? image.substring(colon + 1) : "latest";
        log.info("Pulling image {}", image);
        DockerEngineClient.Response pulled = docker.call("POST",
                "/images/create?fromImage=" + encode(repo) + "&tag=" + encode(tag), null, PULL_TIMEOUT);
        expectOk(pulled, "pull image " + image);
        if (pulled.body().contains("\"error\"")) {
            throw new IOException("Docker could not pull " + image + ": " + pulled.body().trim());
        }
    }

    private static boolean isPortConflict(String body) {
        String b = body.toLowerCase(Locale.ROOT);
        // Linux: "port is already allocated" / "address already in use";
        // Docker Desktop on Windows: "Ports are not available: exposing port TCP 0.0.0.0:8081 ... bind: ..."
        return b.contains("port is already allocated") || b.contains("address already in use")
                || b.contains("ports are not available");
    }

    private URI apiUri(String name) {
        return URI.create("http://" + name + ":" + cfg.apiPort());
    }

    private static Map<String, String> labels(String role, String warehouse, int index) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Names.LABEL_MANAGED, "true");
        labels.put(Names.LABEL_ROLE, role);
        labels.put(Names.LABEL_WAREHOUSE, warehouse);
        labels.put(Names.LABEL_INDEX, String.valueOf(index));
        return labels;
    }

    private static Integer parseInt(String s) {
        try {
            return s == null || s.isEmpty() ? null : Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void expectOk(DockerEngineClient.Response r, String what) throws IOException {
        if (!r.ok()) throw new IOException("Docker could not " + what + ": HTTP " + r.status() + " " + r.body().trim());
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        docker.close();
    }
}
