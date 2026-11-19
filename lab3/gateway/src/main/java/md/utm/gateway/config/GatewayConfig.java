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

/** Wires the gateway's parts together from {@link GatewayProperties}. */
@Configuration
public class GatewayConfig {

    @Bean
    BackendRegistry backendRegistry(GatewayProperties props) {
        return new BackendRegistry(props.backends());
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
        return new ProxyService(registry, balancer, client, cache, props.cache());
    }
}
