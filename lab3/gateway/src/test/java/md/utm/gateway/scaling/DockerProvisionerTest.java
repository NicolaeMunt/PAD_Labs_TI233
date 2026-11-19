package md.utm.gateway.scaling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import md.utm.gateway.config.GatewayProperties;
import md.utm.gateway.scaling.InstanceProvisioner.DbNode;
import md.utm.gateway.scaling.InstanceProvisioner.Instance;
import md.utm.gateway.scaling.InstanceProvisioner.Inventory;

/** DockerProvisioner against a fake Docker Engine listening on a real Unix socket. */
class DockerProvisionerTest {

    record Call(String method, String path, String body) { }

    private static final Pattern HOST_PORT = Pattern.compile("\"HostPort\":\"(\\d+)\"");

    private Path socket;
    private ServerSocketChannel server;
    private Thread acceptor;
    private DockerProvisioner provisioner;

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();
    /** container id → host port it asked for */
    private final ConcurrentHashMap<String, Integer> portOf = new ConcurrentHashMap<>();

    // behaviour switches of the fake engine
    private volatile String containerList = "[]";
    private final Set<Integer> portsTakenOnHost = ConcurrentHashMap.newKeySet();
    private volatile boolean mongoImageMissing;
    private volatile boolean pulled;
    private volatile boolean mongoContainerExists;
    private volatile boolean apiNameTakenByStranger;

    @BeforeEach
    void setUp() throws IOException {
        socket = Path.of(System.getProperty("java.io.tmpdir"), "gv-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        acceptor = Thread.ofVirtual().start(this::serve);
        provisioner = new DockerProvisioner(new GatewayProperties.Provisioner(true, socket.toString(),
                "gamevault-api:latest", "mongo:8", "gamevault-net", 8080, 8081, 8083, "0.25",
                "redis", 6379, 3, 5, 5, Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    @AfterEach
    void tearDown() throws IOException {
        provisioner.close();
        server.close();
        acceptor.interrupt();
        Files.deleteIfExists(socket);
    }

    // ---------------------------------------------------------------- API instances

    @Test
    void startApiCreatesAPublishedContainerWithTheGivenMongoUri() throws Exception {
        String uri = "mongodb://mongo-warehouse-2-1:27017,mongo-warehouse-2-2:27017/?replicaSet=rs-warehouse-2&readPreferenceTags=node:1";
        Instance i = provisioner.startApi("warehouse-2", 1, uri);

        assertThat(i.name()).isEqualTo("api-warehouse-2-1");
        assertThat(i.warehouse()).isEqualTo("warehouse-2");
        assertThat(i.index()).isEqualTo(1);
        assertThat(i.baseUri()).isEqualTo(URI.create("http://api-warehouse-2-1:8080"));
        assertThat(i.hostPort()).isEqualTo(8081);

        Call create = only("POST", "/containers/create?name=api-warehouse-2-1");
        assertThat(create.body())
                .contains("\"Image\":\"gamevault-api:latest\"")
                .contains("\"APP_INSTANCE_ID=api-warehouse-2-1\"")
                .contains("\"APP_WAREHOUSE=warehouse-2\"")
                .contains("\"SPRING_MONGODB_URI=" + uri + "\"")
                .contains("\"SPRING_DATA_REDIS_HOST=redis\"")
                .contains("\"SPRING_DATA_REDIS_PORT=6379\"")
                .contains("\"gamevault.managed\":\"true\"")
                .contains("\"gamevault.role\":\"api\"")
                .contains("\"gamevault.warehouse\":\"warehouse-2\"")
                .contains("\"gamevault.index\":\"1\"")
                .contains("\"gamevault.host-port\":\"8081\"")
                .contains("\"ExposedPorts\":{\"8080/tcp\":{}}")
                .contains("\"PortBindings\":{\"8080/tcp\":[{\"HostPort\":\"8081\"}]}")
                .contains("\"NetworkMode\":\"gamevault-net\"")
                .contains("\"RestartPolicy\":{\"Name\":\"unless-stopped\"}")
                .doesNotContain("com.docker.compose");
        assertThat(calls).extracting(Call::method, Call::path)
                .contains(tuple("POST", "/containers/" + i.id() + "/start"));
    }

    @Test
    void picksTheLowestPortNotUsedByAnotherInstance() throws Exception {
        containerList = "[" + apiEntry("a1", "api-warehouse-1-1", "warehouse-1", 1, 8081) + "]";
        assertThat(provisioner.startApi("warehouse-2", 1, "mongodb://x").hostPort()).isEqualTo(8082);
    }

    @Test
    void retriesTheNextPortWhenTheHostPortIsTaken() throws Exception {
        portsTakenOnHost.add(8081);
        Instance i = provisioner.startApi("warehouse-2", 1, "mongodb://x");

        assertThat(i.hostPort()).isEqualTo(8082);
        // the first container (port 8081) was deleted again
        String firstId = calls.stream().filter(c -> c.path().startsWith("/containers/") && c.path().endsWith("/start"))
                .findFirst().orElseThrow().path().split("/")[2];
        assertThat(portOf.get(firstId)).isEqualTo(8081);
        assertThat(calls).extracting(Call::method, Call::path).contains(tuple("DELETE", "/containers/" + firstId + "?force=true"));
    }

    @Test
    void failsWhenNoPortInTheRangeIsFree() {
        portsTakenOnHost.addAll(Set.of(8081, 8082, 8083));
        assertThatThrownBy(() -> provisioner.startApi("warehouse-2", 1, "mongodb://x"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("8081-8083");
    }

    @Test
    void leftOverApiContainerOfOursIsReplaced() throws Exception {
        apiNameTakenByStranger = false;
        containerNameTaken.add("api-warehouse-2-1");
        Instance i = provisioner.startApi("warehouse-2", 1, "mongodb://x");
        assertThat(i.name()).isEqualTo("api-warehouse-2-1");
        assertThat(calls).extracting(Call::method, Call::path)
                .contains(tuple("DELETE", "/containers/api-warehouse-2-1?force=true"));
    }

    @Test
    void containerNameTakenBySomethingElseIsAnError() {
        apiNameTakenByStranger = true;
        containerNameTaken.add("api-warehouse-2-1");
        assertThatThrownBy(() -> provisioner.startApi("warehouse-2", 1, "mongodb://x"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not created by the gateway");
    }

    @Test
    void stopApiStopsThenRemoves() throws Exception {
        provisioner.stopApi("abc123");
        assertThat(calls).extracting(Call::method, Call::path).containsExactly(
                tuple("POST", "/containers/abc123/stop?t=10"),
                tuple("DELETE", "/containers/abc123?force=true"));
    }

    // ---------------------------------------------------------------- Mongo nodes

    @Test
    void startDbNodeCreatesItsVolumeFirstThenAReplicaSetMember() throws Exception {
        DbNode n = provisioner.startDbNode("warehouse-2", 3);

        assertThat(n.name()).isEqualTo("mongo-warehouse-2-3");
        assertThat(n.host()).isEqualTo("mongo-warehouse-2-3:27017");
        assertThat(n.index()).isEqualTo(3);
        assertThat(calls.get(0).path()).isEqualTo("/volumes/create");
        assertThat(calls.get(0).body())
                .contains("\"Name\":\"gamevault-mongo-warehouse-2-3\"")
                .contains("\"gamevault.role\":\"mongo\"")
                .contains("\"gamevault.index\":\"3\"");

        Call create = only("POST", "/containers/create?name=mongo-warehouse-2-3");
        assertThat(create.body())
                .contains("\"Image\":\"mongo:8\"")
                .contains("\"Cmd\":[\"mongod\",\"--replSet\",\"rs-warehouse-2\",\"--bind_ip_all\",\"--wiredTigerCacheSizeGB\",\"0.25\"]")
                .contains("\"Binds\":[\"gamevault-mongo-warehouse-2-3:/data/db\"]")
                .contains("\"NetworkMode\":\"gamevault-net\"")
                .contains("\"gamevault.managed\":\"true\"")
                .doesNotContain("PortBindings");
        assertThat(calls).extracting(Call::method, Call::path).contains(tuple("POST", "/containers/" + n.id() + "/start"));
    }

    @Test
    void missingMongoImageIsPulledThenTheNodeIsCreated() throws Exception {
        mongoImageMissing = true;
        DbNode n = provisioner.startDbNode("warehouse-2", 1);

        assertThat(n.name()).isEqualTo("mongo-warehouse-2-1");
        assertThat(calls).extracting(Call::method, Call::path).containsSubsequence(
                tuple("POST", "/containers/create?name=mongo-warehouse-2-1"),
                tuple("POST", "/images/create?fromImage=mongo&tag=8"),
                tuple("POST", "/containers/create?name=mongo-warehouse-2-1"));
    }

    @Test
    void existingMongoContainerOfOursIsKeptAndStarted() throws Exception {
        mongoContainerExists = true;
        DbNode n = provisioner.startDbNode("warehouse-2", 1);

        assertThat(n.id()).isEqualTo("existing-mongo");
        assertThat(calls).extracting(Call::method).doesNotContain("DELETE");
        assertThat(calls).extracting(Call::method, Call::path).contains(tuple("POST", "/containers/existing-mongo/start"));
    }

    @Test
    void removeDbNodeRemovesTheContainerAndDeletesItsVolume() throws Exception {
        provisioner.removeDbNode(new DbNode("m1", "mongo-warehouse-2-2", "warehouse-2", 2, "mongo-warehouse-2-2:27017"));
        assertThat(calls).extracting(Call::method, Call::path).containsExactly(
                tuple("POST", "/containers/m1/stop?t=10"),
                tuple("DELETE", "/containers/m1?force=true"),
                tuple("DELETE", "/volumes/gamevault-mongo-warehouse-2-2"));
    }

    // ---------------------------------------------------------------- discovery and parsing

    @Test
    void discoverSplitsApisAndMongoNodesAndSkipsIncompleteEntries() throws Exception {
        containerList = "[" + apiEntry("a1", "api-warehouse-4-2", "warehouse-4", 2, 8085) + ","
                + "{\"Id\":\"m1\",\"Names\":[\"/mongo-warehouse-4-1\"],\"Labels\":{\"gamevault.managed\":\"true\","
                + "\"gamevault.role\":\"mongo\",\"gamevault.warehouse\":\"warehouse-4\",\"gamevault.index\":\"1\"}},"
                + "{\"Id\":\"x\",\"Names\":[\"/broken\"],\"Labels\":{\"gamevault.managed\":\"true\",\"gamevault.role\":\"api\"}}]";

        Inventory inv = provisioner.discover();

        assertThat(inv.apis()).containsExactly(new Instance("a1", "api-warehouse-4-2", "warehouse-4", 2,
                URI.create("http://api-warehouse-4-2:8080"), 8085));
        assertThat(inv.dbNodes()).containsExactly(new DbNode("m1", "mongo-warehouse-4-1", "warehouse-4", 1,
                "mongo-warehouse-4-1:27017"));
        assertThat(calls.get(0).path()).startsWith("/containers/json?all=true&filters=").contains("gamevault.managed%3Dtrue");
    }

    @Test
    void parsesPlainAndChunkedResponses() throws IOException {
        var plain = DockerEngineClient.parse(bytes("HTTP/1.0 201 Created\r\nContent-Type: application/json\r\n\r\n{\"Id\":\"x\"}"));
        assertThat(plain.status()).isEqualTo(201);
        assertThat(plain.body()).isEqualTo("{\"Id\":\"x\"}");

        var chunked = DockerEngineClient.parse(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + "4\r\n[{\"a\r\n5\r\n\":1}]\r\n0\r\n\r\n"));
        assertThat(chunked.body()).isEqualTo("[{\"a\":1}]");
    }

    // ---------------------------------------------------------------- fake Docker Engine

    private final Set<String> containerNameTaken = ConcurrentHashMap.newKeySet();

    private void serve() {
        while (server.isOpen()) {
            try (SocketChannel c = server.accept()) {
                InputStream in = Channels.newInputStream(c);
                OutputStream out = Channels.newOutputStream(c);
                String head = readHead(in);
                String[] requestLine = head.lines().findFirst().orElseThrow().split(" ");
                int length = head.lines().filter(l -> l.toLowerCase().startsWith("content-length:"))
                        .map(l -> Integer.parseInt(l.substring(15).trim())).findFirst().orElse(0);
                String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
                calls.add(new Call(requestLine[0], requestLine[1], body));
                out.write(bytes(answer(requestLine[0], requestLine[1], body)));
                out.flush();
            } catch (IOException e) {
                return;                                    // server closed
            }
        }
    }

    private String answer(String method, String path, String body) {
        if (method.equals("GET") && path.startsWith("/containers/json")) {
            // chunked, like a real engine may answer
            int half = containerList.length() / 2;
            return "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + Integer.toHexString(half) + "\r\n" + containerList.substring(0, half) + "\r\n"
                    + Integer.toHexString(containerList.length() - half) + "\r\n" + containerList.substring(half) + "\r\n0\r\n\r\n";
        }
        if (method.equals("POST") && path.equals("/volumes/create")) {
            return json(201, "{\"Name\":\"v\"}");
        }
        if (method.equals("POST") && path.startsWith("/images/create")) {
            pulled = true;
            return "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "1c\r\n{\"status\":\"Pulling from x\"}\n\r\n"
                    + "16\r\n{\"status\":\"Download\"}\n\r\n0\r\n\r\n";
        }
        if (method.equals("POST") && path.startsWith("/containers/create")) {
            String name = path.substring(path.indexOf("name=") + 5);
            if (name.startsWith("mongo-")) {
                if (mongoContainerExists) return json(409, "{\"message\":\"Conflict. The container name is already in use\"}");
                if (mongoImageMissing && !pulled) return json(404, "{\"message\":\"No such image: mongo:8\"}");
            }
            if (containerNameTaken.remove(name)) return json(409, "{\"message\":\"Conflict. The container name is already in use\"}");
            String id = "id-" + name + "-" + ids.incrementAndGet();
            Matcher m = HOST_PORT.matcher(body);
            if (m.find()) portOf.put(id, Integer.parseInt(m.group(1)));
            return json(201, "{\"Id\":\"" + id + "\"}");
        }
        if (method.equals("GET") && path.endsWith("/json") && path.startsWith("/containers/")) {
            boolean ours = path.contains("mongo-") || !apiNameTakenByStranger;
            String id = path.contains("mongo-") ? "existing-mongo" : "existing-api";
            return json(200, "{\"Id\":\"" + id + "\",\"Config\":{\"Labels\":"
                    + (ours ? "{\"gamevault.managed\":\"true\"}" : "{}") + "}}");
        }
        if (method.equals("POST") && path.endsWith("/start")) {
            String id = path.split("/")[2];
            Integer port = portOf.get(id);
            if (port != null && portsTakenOnHost.contains(port)) {
                return json(500, "{\"message\":\"driver failed programming external connectivity on endpoint: "
                        + "Bind for 0.0.0.0:" + port + " failed: port is already allocated\"}");
            }
            return "HTTP/1.1 204 No Content\r\n\r\n";
        }
        return "HTTP/1.1 204 No Content\r\n\r\n";
    }

    private static String json(int status, String body) {
        return "HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\n\r\n" + body;
    }

    private static String apiEntry(String id, String name, String warehouse, int index, int port) {
        return "{\"Id\":\"" + id + "\",\"Names\":[\"/" + name + "\"],\"Labels\":{\"gamevault.managed\":\"true\","
                + "\"gamevault.role\":\"api\",\"gamevault.warehouse\":\"" + warehouse + "\",\"gamevault.index\":\""
                + index + "\",\"gamevault.host-port\":\"" + port + "\"}}";
    }

    private Call only(String method, String path) {
        List<Call> matching = calls.stream().filter(c -> c.method().equals(method) && c.path().equals(path)).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            String s = head.toString(StandardCharsets.ISO_8859_1);
            if (s.endsWith("\r\n\r\n")) return s;
        }
        throw new IOException("connection closed");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
