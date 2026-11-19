package md.utm.gateway.proxy;

/** The gateway could not get an answer from any backend. Turned into 502 / 503 / 504. */
public class GatewayException extends RuntimeException {

    private final int status;

    public GatewayException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
