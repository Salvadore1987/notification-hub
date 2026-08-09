package uz.hamkorbank.commhub.adapter.in.callback;

/**
 * A callback did not prove it comes from the provider it claims to be (SEC-07).
 *
 * <p>The message names what failed for the log; the answer to the caller does not. A webhook that is
 * told whether it was the address or the secret that was wrong is a webhook that can be probed.
 */
public class CallbackAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String providerCode;

    public CallbackAuthenticationException(String providerCode, String message) {
        super(message);
        this.providerCode = providerCode;
    }

    public String providerCode() {
        return providerCode;
    }
}
