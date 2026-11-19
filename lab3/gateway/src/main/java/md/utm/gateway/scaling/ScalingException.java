package md.utm.gateway.scaling;

/** A scaling request that cannot be carried out; {@code status} is the HTTP status to answer with. */
public class ScalingException extends RuntimeException {

    private final int status;

    public ScalingException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ScalingException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
