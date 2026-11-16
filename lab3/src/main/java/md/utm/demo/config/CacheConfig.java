package md.utm.demo.config;

import md.utm.demo.game.dto.GameResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    private final Duration gamesTtl;
    private final Duration defaultTtl;

    public CacheConfig(@Value("${app.cache.games-ttl:10m}") Duration gamesTtl,
                       @Value("${app.cache.default-ttl:5m}") Duration defaultTtl) {
        this.gamesTtl = gamesTtl;
        this.defaultTtl = defaultTtl;
    }

    @Bean
    RedisCacheManagerBuilderCustomizer cacheCustomizer() {
        return builder -> builder
                .cacheDefaults(defaults(defaultTtl))
                .withCacheConfiguration("games", typed(GameResponse.class, gamesTtl));
    }

    private RedisCacheConfiguration defaults(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .prefixCacheNameWith("gamevault:v1:");
    }

    private RedisCacheConfiguration typed(Class<?> type, Duration ttl) {
        return defaults(ttl).serializeValuesWith(
                SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(type)));
    }
}