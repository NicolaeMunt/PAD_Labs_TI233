package md.utm.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Starts the real Spring Boot gateway (no Redis: cache disabled) in front of two stub
 * API instances and calls it over HTTP like a client would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTest {

    static final StubBackend api1 = start("api-1");
    static final StubBackend api2 = start("api-2");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("gateway.backends", () -> api1.uri() + "," + api2.uri());
        r.add("gateway.strategy", () -> "least-connections");
        r.add("gateway.cache.enabled", () -> "false");
    }

    @AfterAll
    static void stop() {
        api1.close();
        api2.close();
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void forwardsToBothInstancesWithGatewayHeaders() throws Exception {
        HttpResponse<String> r1 = send("/whoami", "application/json");
        HttpResponse<String> r2 = send("/whoami", "application/json");

        assertThat(r1.statusCode()).isEqualTo(200);
        assertThat(r1.headers().firstValue("X-Cache")).hasValue("BYPASS");
        assertThat(r1.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(r1.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(java.util.List.of(r1.headers().firstValue("X-Instance-Id").orElseThrow(),
                                     r2.headers().firstValue("X-Instance-Id").orElseThrow()))
                .containsExactlyInAnyOrder("api-1", "api-2");
    }

    @Test
    void passesQueryStringAndAcceptThrough() throws Exception {
        HttpResponse<String> r = send("/api/games?genre=RPG&size=5", "application/xml");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/xml");
        assertThat(r.body()).contains("<path>/api/games</path>");
    }

    @Test
    void stateEndpointListsBackends() throws Exception {
        HttpResponse<String> r = send("/gateway/state", "application/json");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"strategy\":\"least-connections\"").contains("\"backends\"")
                .contains("\"warehouse\":\"warehouse-1\"");
    }

    @Test
    void answersCarryTheWarehouseAndUnknownWarehousesAre404() throws Exception {
        assertThat(send("/whoami", "application/json").headers().firstValue("X-Warehouse")).hasValue("warehouse-1");

        HttpResponse<String> unknown = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/whoami"))
                .header("X-Warehouse", "warehouse-9").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("Unknown warehouse 'warehouse-9'");
    }

    @Test
    void warehousesEndpointListsThemAndScalingIsOffWithoutDocker() throws Exception {
        HttpResponse<String> list = send("/gateway/warehouses", "application/json");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("\"name\":\"warehouse-1\"").contains("\"database\":\"gamevault_warehouse-1\"")
                .contains("\"replicas\":2").contains("\"scalingEnabled\":false");

        HttpResponse<String> scale = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/gateway/warehouses/warehouse-4"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"replicas\":2}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(scale.statusCode()).isEqualTo(501);
        assertThat(scale.body()).contains("gateway.provisioner.enabled");
    }

    private HttpResponse<String> send(String path, String accept) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", accept).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static StubBackend start(String name) {
        try {
            return new StubBackend(name);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
