package uz.hamkorbank.commhub.adapter.out.provider.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Switch of the local stand's plaintext trace of what is being sent (OBS-03, SEC-06).
 *
 * <p>Off unless it is switched on explicitly, and there is deliberately no {@code matchIfMissing}
 * anywhere near it: the trace prints a recipient and a message body — which for the Hub's main use
 * case is a one-time password — so the default has to be the safe one and the switch has to be a
 * decision somebody wrote down. The one place it is on is {@code config/application.yml} at the
 * repository root, which reaches neither the jar nor the image.
 *
 * @param enabled {@code true} writes one line per submission with the address and the text unmasked
 */
@ConfigurationProperties("commhub.provider.content-log")
public record ContentLogProperties(Boolean enabled) {

    public ContentLogProperties {
        enabled = enabled != null && enabled;
    }

    public static ContentLogProperties disabled() {
        return new ContentLogProperties(null);
    }
}
