package md.utm.gateway.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import md.utm.gateway.backend.BackendRegistry;
import md.utm.gateway.backend.HealthChecker;
import md.utm.gateway.balancer.LeastConnectionsBalancer;
import md.utm.gateway.balancer.LoadBalancer;
import md.utm.gateway.balancer.RoundRobinBalancer;
import md.utm.gateway.cache.RedisResponseCache;
import md.utm.gateway.cache.ResponseCache;
import md.utm.gateway.proxy.BackendClient;
import md.utm.gateway.proxy.ProxyService;
import md.utm.gateway.scaling.DockerProvisioner;
import md.utm.gateway.scaling.InstanceProvisioner;
import md.utm.gateway.scaling.MongoReplicaSet;
import md.utm.gateway.scaling.ReplicaSet;
import md.utm.gateway.scaling.WarehouseManager;

/** Wires the gateway's parts together from {@link GatewayProperties}. */
@Configuration
public class GatewayConfig {

    @Bean
    BackendRegistry backendRegistry(GatewayProperties props) {
        return BackendRegistry.fromConfig(props.backends(), props.defaultWarehouse());
    }

    @Bean
    InstanceProvisioner instanceProvisioner(GatewayProperties props) {
        return props.provisioner().enabled()
                ? new DockerProvisioner(props.provisioner())
                : InstanceProvisioner.DISABLED;
    }

    @Bean(destroyMethod = "close")
    ReplicaSet replicaSet() {
        return new MongoReplicaSet();
    }

    /**
     * start: takes back the containers of an earlier run, creates the initial warehouses that are
     * missing, and starts watching the replica sets.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    WarehouseManager warehouseManager(BackendRegistry registry, InstanceProvisioner provisioner,
                                      ReplicaSet replicaSet, GatewayProperties props) {
        return new WarehouseManager(registry, provisioner, replicaSet, props);
    }

    @Bean
    LoadBalancer loadBalancer(GatewayProperties props) {
        return switch (props.strategy()) {
            case ROUND_ROBIN -> new RoundRobinBalancer();
            case LEAST_CONNECTIONS -> new LeastConnectionsBalancer();
        };
    }

    @Bean(destroyMethod = "close")
    BackendClient backendClient(GatewayProperties props) {
        return new BackendClient(props.pool());
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    HealthChecker healthChecker(BackendRegistry registry, BackendClient client, GatewayProperties props) {
        return new HealthChecker(registry, client, props.health());
    }

    @Bean
    ResponseCache responseCache(GatewayProperties props, ObjectProvider<StringRedisTemplate> redis) {
        return props.cache().enabled()
                ? new RedisResponseCache(redis.getObject(), props.cache().ttl())
                : ResponseCache.DISABLED;
    }

    @Bean
    ProxyService proxyService(BackendRegistry registry, LoadBalancer balancer, BackendClient client,
                              ResponseCache cache, GatewayProperties props) {
        return new ProxyService(registry, balancer, client, cache, props.cache(), props.defaultWarehouse());
    }
}
