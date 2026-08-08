package uz.hamkorbank.commhub.domain.model.vo;

import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Technical adapter behind a provider profile, e.g. {@code playmobile-http}, {@code smsgate-http},
 * {@code smtp}, {@code fcm-http-v1}, {@code apns-http2} (§10.1 {@code provider.adapter_type}).
 *
 * <p>The application layer resolves the channel port implementation by this value. It stays an
 * opaque string so that adding a provider adapter needs no domain change (AR-04).
 */
public record AdapterType(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    public AdapterType {
        Guard.matches(value, PATTERN, "AdapterType.value");
    }

    public static AdapterType of(String value) {
        return new AdapterType(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
