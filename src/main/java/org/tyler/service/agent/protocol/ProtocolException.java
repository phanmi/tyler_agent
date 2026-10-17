package org.tyler.service.agent.protocol;

/** Safe transport failure code. Raw frames, parser messages, and credentials are never retained. */
public final class ProtocolException extends RuntimeException {
    private final String code;

    public ProtocolException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
