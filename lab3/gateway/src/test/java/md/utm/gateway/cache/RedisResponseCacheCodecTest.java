package md.utm.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import md.utm.gateway.proxy.Header;
import md.utm.gateway.proxy.ProxyResponse;

class RedisResponseCacheCodecTest {

    @Test
    void encodeDecodeKeepsStatusContentTypeInstanceAndBody() {
        byte[] body = "<game><title>Doom\nII</title></game>".getBytes(StandardCharsets.UTF_8);
        ProxyResponse original = new ProxyResponse(200, List.of(
                new Header("Content-Type", "application/xml;charset=UTF-8"),
                new Header("X-Instance-Id", "api-2"),
                new Header("Vary", "Accept")), body);

        ProxyResponse copy = RedisResponseCache.decode(RedisResponseCache.encode(original));

        assertThat(copy.status()).isEqualTo(200);
        assertThat(copy.header("Content-Type")).isEqualTo("application/xml;charset=UTF-8");
        assertThat(copy.header("X-Instance-Id")).isEqualTo("api-2");
        assertThat(copy.body()).isEqualTo(body);
    }

    @Test
    void emptyBodyAndMissingHeadersSurvive() {
        ProxyResponse copy = RedisResponseCache.decode(RedisResponseCache.encode(
                new ProxyResponse(200, List.of(), new byte[0])));
        assertThat(copy.headers()).isEmpty();
        assertThat(copy.body()).isEmpty();
    }
}
