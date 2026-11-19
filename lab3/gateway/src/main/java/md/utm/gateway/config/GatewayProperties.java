package md.utm.gateway.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import md.utm.gateway.backend.Warehouses;

/**
 * Everything under {@code gateway.*} in application.properties.
 *
 * @param backends         the fixed API instances (DW nodes): {@code warehouse=URL}, or a bare URL
 *                         for the default warehouse, e.g. {@code warehouse-2=http://api-2:8080}
 * @param strategy         load-balancing algorithm (inside one warehouse)
 * @param defaultWarehouse warehouse for requests without an X-Warehouse header
 * @param health            active health checks of the backends
 * @param provisioner       starting and stopping containers at runtime
 * @param initialWarehouses warehouses the gateway creates (1 API instance each) when they do not exist yet
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue List<String> backends,
        @DefaultValue("round-robin") Strategy strategy,
        @DefaultValue(Warehouses.DEFAULT) String defaultWarehouse,
        @DefaultValue Health health,
        @DefaultValue Pool pool,
        @DefaultValue Cache cache,
        @DefaultValue Provisioner provisioner,
        @DefaultValue List<String> initialWarehouses) {

    public enum Strategy { ROUND_ROBIN, LEAST_CONNECTIONS }

    /**
     * Horizontal scaling: the gateway starts each warehouse's API containers and its own MongoDB
     * replica set (as many copies as API instances, at least {@code minDbCopies}) through the
     * Docker Engine API. Names and labels: see {@link md.utm.gateway.scaling.Names}.
     *
     * @param enabled       off by default (needs the Docker socket mounted into the gateway)
     * @param dockerSocket  path of the Docker Engine's Unix socket
     * @param apiImage      API image, built by docker compose
     * @param mongoImage    MongoDB image (pulled if missing)
     * @param network       Docker network the gateway, the containers and Redis are on
     * @param apiPort       port the API listens on inside its container
     * @param hostPortFrom  first host port for direct calls to API instances
     * @param hostPortTo    last host port for direct calls to API instances
     * @param mongoCacheGb  --wiredTigerCacheSizeGB of every Mongo node (keeps many nodes affordable)
     * @param redisHost     SPRING_DATA_REDIS_HOST for API instances
     * @param redisPort     SPRING_DATA_REDIS_PORT for API instances
     * @param minDbCopies   a warehouse's replica set never has fewer nodes (failover needs 3)
     * @param maxReplicas   upper limit of API instances (and so of database copies) per warehouse
     * @param maxWarehouses upper limit of warehouses
     * @param drainTimeout  how long a removed API instance may finish its requests before it is stopped
     * @param dockerTimeout a Docker API call slower than this fails
     * @param mongoTimeout  how long to wait for a Mongo node to start, an election or a reconfig
     */
    public record Provisioner(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("/var/run/docker.sock") String dockerSocket,
            @DefaultValue("gamevault-api:latest") String apiImage,
            @DefaultValue("mongo:8") String mongoImage,
            @DefaultValue("gamevault-net") String network,
            @DefaultValue("8080") int apiPort,
            @DefaultValue("8081") int hostPortFrom,
            @DefaultValue("8099") int hostPortTo,
            @DefaultValue("0.25") String mongoCacheGb,
            @DefaultValue("localhost") String redisHost,
            @DefaultValue("6379") int redisPort,
            @DefaultValue("3") int minDbCopies,
            @DefaultValue("5") int maxReplicas,
            @DefaultValue("5") int maxWarehouses,
            @DefaultValue("15s") Duration drainTimeout,
            @DefaultValue("30s") Duration dockerTimeout,
            @DefaultValue("90s") Duration mongoTimeout) { }

    /**
     * @param interval           how often every backend's /actuator/health is polled
     * @param timeout            a poll slower than this counts as failed
     * @param failuresBeforeDown consecutive failed polls before a backend is taken out
     *                           (a failed client request takes it out immediately)
     */
    public record Health(
            @DefaultValue("2s") Duration interval,
            @DefaultValue("2s") Duration timeout,
            @DefaultValue("2") int failuresBeforeDown) { }

    /**
     * Pool of keep-alive connections from the gateway to the backends.
     *
     * @param maxTotal        connections to all backends together
     * @param maxPerBackend   connections to one backend
     * @param connectTimeout  how long to wait for a TCP connect
     * @param responseTimeout how long to wait for a backend's answer before failing over
     * @param idleTimeout     idle pooled connections are closed after this
     */
    public record Pool(
            @DefaultValue("200") int maxTotal,
            @DefaultValue("50") int maxPerBackend,
            @DefaultValue("1s") Duration connectTimeout,
            @DefaultValue("10s") Duration responseTimeout,
            @DefaultValue("15s") Duration idleTimeout) { }

    /**
     * Response cache in Redis.
     *
     * @param enabled      turn the cache off completely
     * @param ttl          how long a cached response lives
     * @param paths        path prefixes whose GET responses are cached
     * @param maxBodyBytes bigger responses are not cached
     */
    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration ttl,
            @DefaultValue("/api/") List<String> paths,
            @DefaultValue("1048576") int maxBodyBytes) { }
}
