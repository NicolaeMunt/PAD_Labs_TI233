package md.utm.gateway.scaling;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal client for the Docker Engine HTTP API over its Unix socket (/var/run/docker.sock).
 *
 * One connection per call, HTTP/1.0: Docker then sends a plain body and closes the
 * connection, so the response is simply everything until end of stream. That is enough for
 * the few JSON calls the provisioner makes, without a Docker client library.
 */
class DockerEngineClient implements AutoCloseable {

    record Response(int status, String body) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    private final UnixDomainSocketAddress address;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();
    /** runs each call so it can be given a timeout (a blocking socket read has none) */
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    DockerEngineClient(Path socket, Duration timeout) {
        this.address = UnixDomainSocketAddress.of(socket);
        this.timeout = timeout;
    }

    Response call(String method, String path, Object body) throws IOException {
        return call(method, path, body, timeout);
    }

    /** Same as {@link #call(String, String, Object)} with its own timeout (e.g. a long image pull). */
    Response call(String method, String path, Object body, Duration timeout) throws IOException {
        byte[] payload = body == null ? new byte[0] : json.writeValueAsBytes(body);
        Future<byte[]> call = calls.submit(() -> exchange(method, path, payload));
        try {
            return parse(call.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            call.cancel(true);              // interrupting the thread closes its socket channel
            throw new IOException("Docker did not answer " + method + " " + path + " within " + timeout);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Docker call " + method + " " + path + " failed", e.getCause());
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while calling Docker");
        }
    }

    JsonNode parseJson(String body) {
        return json.readTree(body);
    }

    private byte[] exchange(String method, String path, byte[] payload) throws IOException {
        try (SocketChannel channel = SocketChannel.open(address)) {
            String head = method + " " + path + " HTTP/1.0\r\n"
                    + "Host: docker\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + payload.length + "\r\n\r\n";
            byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer out = ByteBuffer.allocate(headBytes.length + payload.length).put(headBytes).put(payload).flip();
            while (out.hasRemaining()) channel.write(out);
            return Channels.newInputStream(channel).readAllBytes();
        }
    }

    /** Splits a raw HTTP response into status and body; decodes a chunked body if needed. */
    static Response parse(byte[] raw) throws IOException {
        int split = indexOf(raw, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        if (split < 0) throw new IOException("Malformed response from Docker");
        String head = new String(raw, 0, split, StandardCharsets.ISO_8859_1);
        byte[] body = Arrays.copyOfRange(raw, split + 4, raw.length);

        String[] statusLine = head.lines().findFirst().orElse("").split(" ");
        if (statusLine.length < 2) throw new IOException("Malformed status line from Docker: " + head);
        int status = Integer.parseInt(statusLine[1]);
        if (head.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) {
            body = dechunk(body);
        }
        return new Response(status, new String(body, StandardCharsets.UTF_8));
    }

    static byte[] dechunk(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < body.length) {
            int lineEnd = indexOf(body, "\r\n".getBytes(StandardCharsets.US_ASCII), pos);
            if (lineEnd < 0) throw new IOException("Malformed chunked body from Docker");
            String sizeField = new String(body, pos, lineEnd - pos, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(sizeField.split(";")[0].trim(), 16);
            if (size == 0) break;
            int start = lineEnd + 2;
            if (start + size > body.length) throw new IOException("Truncated chunked body from Docker");
            out.write(body, start, size);
            pos = start + size + 2;                     // skip the CRLF after the chunk
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        return indexOf(data, pattern, 0);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    @Override
    public void close() {
        calls.shutdownNow();
    }
}
