package uz.hamkorbank.commhub.adapter.out.secret;

/**
 * A credential the configuration points at is not in the secret store (SEC-04).
 *
 * <p>Fails the call rather than falling back to an empty credential: a provider adapter that sends
 * with a blank password gets an authentication error from the provider, and the operator then debugs
 * the provider instead of the deployment.
 *
 * <p>The message names the reference only. The reference is not itself a secret — it is stored in the
 * {@code provider} table and shown in the admin panel.
 */
public class SecretNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String secretRef;

    public SecretNotFoundException(String secretRef) {
        super("no secret is available under reference " + secretRef);
        this.secretRef = secretRef;
    }

    public String secretRef() {
        return secretRef;
    }
}
