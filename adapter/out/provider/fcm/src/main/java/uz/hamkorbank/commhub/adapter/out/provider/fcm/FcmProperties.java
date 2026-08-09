package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/**
 * Settings of the Firebase Cloud Messaging integration (§9.4.1, PU-01…PU-05).
 *
 * <p>There is no {@code project-id} here on purpose: it is part of the service account key, and a
 * deployment that could name one project in the yaml and another in the key would fail at the first
 * send with an error that names neither.
 *
 * <p>Retry inside a delivery attempt is allowed and is the default, unlike SMS Gate: HTTP v1 is
 * idempotent per {@code message.token} only in the sense that a repeat is a second notification — but
 * FCM's own retryable answers ({@code UNAVAILABLE}, {@code INTERNAL}) mean the message was <em>not</em>
 * accepted, which is exactly the case a retry is for (PU-04). Answers that might have been accepted
 * never reach the retry: they arrive as an ack, not as an exception (PR-01).
 */
@ConfigurationProperties("commhub.provider.fcm")
public record FcmProperties(
        Boolean enabled,
        String providerCode,
        String credentialsRef,
        Sending sending,
        OAuth oauth,
        RateLimit rateLimit,
        ProviderHttpProperties http,
        ProviderResilienceProperties resilience) {

    public static final String DEFAULT_PROVIDER_CODE = "FCM";

    public static final String DEFAULT_BASE_URL = "https://fcm.googleapis.com";

    /** {@code POST /v1/projects/{project}/messages:send} (PU-01); the project comes from the key. */
    public static final String SEND_PATH_TEMPLATE = "/v1/projects/%s/messages:send";

    public FcmProperties {
        enabled = enabled != null && enabled;
        providerCode = providerCode == null || providerCode.isBlank()
                ? DEFAULT_PROVIDER_CODE
                : providerCode.trim().toUpperCase(Locale.ROOT);
        sending = sending == null ? Sending.defaults() : sending;
        oauth = oauth == null ? OAuth.defaults() : oauth;
        rateLimit = rateLimit == null ? RateLimit.unlimited() : rateLimit;
        http = http == null || !http.hasBaseUrl()
                ? new ProviderHttpProperties(
                        DEFAULT_BASE_URL,
                        http == null ? null : http.connectTimeout(),
                        http == null ? null : http.readTimeout())
                : http;
        resilience = resilience == null ? ProviderResilienceProperties.defaults() : resilience;
    }

    public static FcmProperties defaults() {
        return new FcmProperties(null, null, null, null, null, null, null, null);
    }

    public boolean hasCredentials() {
        return credentialsRef != null && !credentialsRef.isBlank();
    }

    /**
     * How a notification is shaped for FCM (PU-03, PU-05).
     *
     * @param priorities traffic class → {@code android.priority}; {@code HIGH} wakes the device, and
     *     PU-03 reserves it for OTP and transactional traffic — a bulk campaign delivered at {@code HIGH}
     *     is a campaign that drains batteries and eventually gets throttled by Google for all traffic
     * @param defaultTtl {@code android.ttl} for a message without a TTL of its own; {@code null} lets FCM
     *     apply its own four weeks, which for a notification is almost never what was meant
     * @param iosDelivery the PU-05 mode: FCM as the single push provider for both platforms, translating
     *     to APNs itself. Off by default because it is a fact about the Bank's iOS application — whether
     *     it embeds the Firebase SDK — and not a preference (open question 12 of §17)
     * @param validateTestSends test sends (FR-7.4) go out as {@code validate_only}, so a test token is
     *     verified end to end without a notification appearing on a customer's phone (PU-13)
     */
    public record Sending(
            Map<TrafficClass, String> priorities, Duration defaultTtl, Boolean iosDelivery, Boolean validateTestSends) {

        public static final String PRIORITY_HIGH = "HIGH";
        public static final String PRIORITY_NORMAL = "NORMAL";

        /** Key of {@code provider.endpoint_config} that switches the PU-05 mode without a restart. */
        public static final String IOS_DELIVERY_KEY = "ios-delivery";

        public static final String DEFAULT_TTL_KEY = "default-ttl-seconds";

        private static final Map<TrafficClass, String> DEFAULT_PRIORITIES = Map.of(
                TrafficClass.CRITICAL_OTP, PRIORITY_HIGH,
                TrafficClass.TRANSACTIONAL, PRIORITY_HIGH,
                TrafficClass.NOTIFICATION, PRIORITY_NORMAL);

        public Sending {
            priorities = priorities == null || priorities.isEmpty()
                    ? DEFAULT_PRIORITIES
                    : Map.copyOf(new EnumMap<>(priorities));
            iosDelivery = iosDelivery != null && iosDelivery;
            validateTestSends = validateTestSends == null || validateTestSends;
        }

        public static Sending defaults() {
            return new Sending(null, null, null, null);
        }

        /** {@code android.priority} of one message (PU-03). */
        public String priorityOf(TrafficClass trafficClass) {
            String priority = priorities.getOrDefault(trafficClass, PRIORITY_NORMAL);
            return PRIORITY_HIGH.equalsIgnoreCase(priority) ? PRIORITY_HIGH : PRIORITY_NORMAL;
        }

        /**
         * These settings with {@code provider.endpoint_config} applied on top (AD-07, §10.1).
         *
         * <p>Keys: {@code ios-delivery}, {@code default-ttl-seconds} and
         * {@code priority.<traffic-class>}. The PU-05 mode belongs here rather than in the yaml because
         * switching it is how an operator moves iOS traffic between FCM and the direct APNs adapter, and
         * that is a routing decision — it must not need a deploy while push is failing (FR-2.7).
         */
        public Sending overlay(Map<String, String> endpointConfig) {
            if (endpointConfig == null || endpointConfig.isEmpty()) {
                return this;
            }
            Map<TrafficClass, String> overlaid = new EnumMap<>(priorities);
            for (TrafficClass trafficClass : TrafficClass.values()) {
                String key = "priority."
                        + trafficClass.name().toLowerCase(Locale.ROOT).replace('_', '-');
                String value = endpointConfig.get(key);
                if (value != null && !value.isBlank()) {
                    overlaid.put(trafficClass, value.trim().toUpperCase(Locale.ROOT));
                }
            }
            return new Sending(
                    overlaid,
                    ttl(endpointConfig.get(DEFAULT_TTL_KEY), defaultTtl),
                    flag(endpointConfig.get(IOS_DELIVERY_KEY), iosDelivery),
                    validateTestSends);
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

        private static boolean flag(String value, boolean fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return "true".equalsIgnoreCase(value.trim()) || "enabled".equalsIgnoreCase(value.trim());
        }
    }

    /**
     * OAuth2 exchange of the service account key for an access token (PU-01).
     *
     * @param tokenUrl Google's token endpoint; overridden only by tests and by a proxied deployment. The
     *     value in the service account key wins when it has one, which is what makes an emulator work
     * @param refreshSkew how long before expiry the token is renewed; a token that expires mid-flight
     *     costs a message its SLA, and Google's are valid for an hour, so the skew is cheap
     */
    public record OAuth(String tokenUrl, Duration refreshSkew) {

        public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";

        /** Scope of FCM HTTP v1 (PU-01). */
        public static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

        public static final Duration DEFAULT_REFRESH_SKEW = Duration.ofMinutes(5);

        public OAuth {
            tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? DEFAULT_TOKEN_URL : tokenUrl.trim();
            refreshSkew = refreshSkew == null || refreshSkew.isNegative() || refreshSkew.isZero()
                    ? DEFAULT_REFRESH_SKEW
                    : refreshSkew;
        }

        public static OAuth defaults() {
            return new OAuth(null, null);
        }
    }
}
