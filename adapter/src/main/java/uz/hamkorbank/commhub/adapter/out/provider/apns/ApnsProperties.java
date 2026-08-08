package uz.hamkorbank.commhub.adapter.out.provider.apns;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.domain.model.RateLimit;

/**
 * Settings of the Apple Push Notification service integration (§9.4.2, PU-06…PU-08, PU-13).
 *
 * <p>Two base URLs rather than one, and they are not a fallback pair: {@code http} is production and
 * {@code sandbox} is the environment a token built by a development or TestFlight build belongs to.
 * Apple refuses a production token on sandbox and a sandbox token on production, so the choice is made
 * per message by the {@code test} flag of the submission (FR-7.4, PU-13) — not per deployment.
 *
 * <p>Retry inside a delivery attempt is off by default. {@code apns-id} makes a repeat idempotent in
 * principle, but APNs answers a refused notification with a {@code reason} rather than a transport
 * failure, and the transport failures that remain — a connection lost mid-request — are exactly the
 * ones where Apple may or may not have queued the notification. The saga's failover covers them without
 * risking a second alert on a customer's lock screen.
 */
@ConfigurationProperties("commhub.provider.apns")
public record ApnsProperties(
        Boolean enabled,
        String providerCode,
        Credentials credentials,
        Sending sending,
        RateLimit rateLimit,
        ProviderHttpProperties http,
        ProviderHttpProperties sandbox,
        ProviderResilienceProperties resilience) {

    public static final String DEFAULT_PROVIDER_CODE = "APNS";

    public static final String DEFAULT_BASE_URL = "https://api.push.apple.com";

    public static final String DEFAULT_SANDBOX_BASE_URL = "https://api.sandbox.push.apple.com";

    /** {@code POST /3/device/{deviceToken}} (PU-06). */
    public static final String SEND_PATH_TEMPLATE = "/3/device/%s";

    public ApnsProperties {
        enabled = enabled != null && enabled;
        providerCode = providerCode == null || providerCode.isBlank()
                ? DEFAULT_PROVIDER_CODE
                : providerCode.trim().toUpperCase(Locale.ROOT);
        credentials = credentials == null ? new Credentials(null, null, null, null) : credentials;
        sending = sending == null ? Sending.defaults() : sending;
        rateLimit = rateLimit == null ? RateLimit.unlimited() : rateLimit;
        http = withBaseUrl(http, DEFAULT_BASE_URL);
        sandbox = withBaseUrl(sandbox, DEFAULT_SANDBOX_BASE_URL);
        // Один вызов на попытку: apns-id делает повтор идемпотентным, но потерянное соединение —
        // это ровно тот случай, когда неизвестно, поставила ли Apple уведомление в очередь (§9.4.2).
        resilience = resilience == null ? ProviderResilienceProperties.withoutInnerRetry() : resilience;
    }

    public static ApnsProperties defaults() {
        return new ApnsProperties(null, null, null, null, null, null, null, null);
    }

    private static ProviderHttpProperties withBaseUrl(ProviderHttpProperties configured, String fallback) {
        if (configured != null && configured.hasBaseUrl()) {
            return configured;
        }
        return new ProviderHttpProperties(
                fallback,
                configured == null ? null : configured.connectTimeout(),
                configured == null ? null : configured.readTimeout());
    }

    /**
     * Token-based authentication of the provider API (PU-06, SEC-04).
     *
     * <p>Token-based rather than certificate-based on purpose: a {@code .p8} key does not expire, is one
     * secret rather than a keystore, and is the form Apple documents for new integrations. The key
     * itself never leaves the secret store — {@link #privateKeyRef} is a reference resolved per call, so
     * a rotation applies without a restart.
     *
     * @param teamId Apple developer team; the {@code iss} claim of the JWT
     * @param keyId identifier of the {@code .p8} key; the {@code kid} header of the JWT
     * @param refreshInterval how often the JWT is re-signed; PU-06 requires between 20 and 60 minutes,
     *     and Apple refuses both a token younger than 20 minutes on a new connection and one older than
     *     an hour
     */
    public record Credentials(String teamId, String keyId, String privateKeyRef, Duration refreshInterval) {

        public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofMinutes(40);

        private static final Duration MIN_REFRESH_INTERVAL = Duration.ofMinutes(20);
        private static final Duration MAX_REFRESH_INTERVAL = Duration.ofMinutes(55);

        public Credentials {
            refreshInterval = clamp(refreshInterval);
        }

        public boolean isConfigured() {
            return teamId != null
                    && !teamId.isBlank()
                    && keyId != null
                    && !keyId.isBlank()
                    && privateKeyRef != null
                    && !privateKeyRef.isBlank();
        }

        /**
         * Keeps the interval inside the window PU-06 allows.
         *
         * <p>Clamped rather than rejected: the cost of refusing to start over a mistyped duration is a
         * push channel that is down, and the correct value is known — unlike a mistyped weight, where
         * guessing would be presumptuous, here the specification states the bounds.
         */
        private static Duration clamp(Duration configured) {
            if (configured == null) {
                return DEFAULT_REFRESH_INTERVAL;
            }
            if (configured.compareTo(MIN_REFRESH_INTERVAL) < 0) {
                return MIN_REFRESH_INTERVAL;
            }
            return configured.compareTo(MAX_REFRESH_INTERVAL) > 0 ? MAX_REFRESH_INTERVAL : configured;
        }
    }

    /**
     * How a notification is addressed on the Apple side (PU-06).
     *
     * @param topic bundle id of the Bank's application; the {@code apns-topic} header, and the one
     *     setting without which nothing is delivered at all
     * @param pushType {@code alert} for a notification the customer sees; {@code background} exists for
     *     silent data pushes and is not what this channel carries
     * @param defaultTtl {@code apns-expiration} for a message without a TTL of its own; {@code null}
     *     means "no expiry", which for a notification is rarely intended but is Apple's own default
     */
    public record Sending(String topic, String pushType, Duration defaultTtl) {

        public static final String PUSH_TYPE_ALERT = "alert";

        /** Key of {@code provider.endpoint_config} that overrides the bundle id without a restart. */
        public static final String TOPIC_KEY = "topic";

        public static final String DEFAULT_TTL_KEY = "default-ttl-seconds";

        public Sending {
            pushType = pushType == null || pushType.isBlank() ? PUSH_TYPE_ALERT : pushType.trim();
        }

        public static Sending defaults() {
            return new Sending(null, null, null);
        }

        public boolean hasTopic() {
            return topic != null && !topic.isBlank();
        }

        /** These settings with {@code provider.endpoint_config} applied on top (AD-07, §10.1). */
        public Sending overlay(Map<String, String> endpointConfig) {
            if (endpointConfig == null || endpointConfig.isEmpty()) {
                return this;
            }
            String configuredTopic = endpointConfig.get(TOPIC_KEY);
            return new Sending(
                    configuredTopic == null || configuredTopic.isBlank() ? topic : configuredTopic.trim(),
                    pushType,
                    ttl(endpointConfig.get(DEFAULT_TTL_KEY), defaultTtl));
        }

        private static Duration ttl(String value, Duration fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                long seconds = Long.parseLong(value.trim());
                return seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }
}
