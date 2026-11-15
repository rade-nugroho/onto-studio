package studio.onto.engine.connector;

/**
 * Gateway-side failure surfaced to engine routes without leaking stack traces.
 */
public final class ConnectorException extends Exception {

    private final String code;

    public ConnectorException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}