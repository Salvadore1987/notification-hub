package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/**
 * Everything the Playmobile integration needs that is not in the message (§9.1, PM-01, PM-03).
 *
 * <p>The values here are the deployment's defaults. Since Phase 8 the settings an operator changes
 * between deploys — the alpha-name, the {@code message-id} prefix, the default TTL and the priority
 * mapping — are overlaid from {@code provider.endpoint_config} through
 * {@link uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRuntimeSettings}, so changing them
 * needs no restart (AD-07, NF-07). Credentials never make that move: they arrive as values filled from
 * the process environment and are never stored by the Hub (SEC-04, ADR-0044), and neither do base URL,
 * timeouts or breaker windows, which decide how the HTTP client itself is built.
 *
 * @param enabled whether the adapter is deployed at all; off leaves the bean uncreated and the router
 *     without a Playmobile adapter, which the sending saga reports as "no adapter" rather than failing
 * @param providerCode code of the {@code provider} row this adapter serves — the same value the router
 *     yields and the callback path carries
 */
@ConfigurationProperties("commhub.provider.playmobile")
public record PlaymobileProperties(
        Boolean enabled,
        String providerCode,
        Credentials credentials,
        Sending sending,
        RateLimit rateLimit,
        ProviderHttpProperties http,
        ProviderResilienceProperties resilience) {

    public static final String DEFAULT_PROVIDER_CODE = "PLAYMOBILE";

    /** {@code POST <base-url>/send} — single and bulk share one endpoint (§9.1). */
    public static final String SEND_PATH = "/send";

    public PlaymobileProperties {
        enabled = enabled != null && enabled;
        providerCode = providerCode == null || providerCode.isBlank()
                ? DEFAULT_PROVIDER_CODE
                : providerCode.trim().toUpperCase(Locale.ROOT);
        credentials = credentials == null ? new Credentials(null, null) : credentials;
        sending = sending == null ? Sending.defaults() : sending;
        rateLimit = rateLimit == null ? RateLimit.unlimited() : rateLimit;
        http = http == null ? ProviderHttpProperties.of(null) : http;
        // Playmobile takes a message-id the Hub generates, so a repeated submission is deduplicated on
        // their side — which is what makes retrying inside a single delivery attempt safe here (§9.1).
        resilience = resilience == null ? ProviderResilienceProperties.defaults() : resilience;
    }

    public static PlaymobileProperties defaults() {
        return new PlaymobileProperties(null, null, null, null, null, null, null);
    }

    /**
     * Basic auth credentials of §9.1, filled from the environment of the pod (SEC-04, ADR-0044).
     *
     * <p>Values, so {@code toString} is overridden: a record prints its components, and this one is a
     * component of the properties tree that a startup banner or a diagnostic log would print whole.
     */
    public record Credentials(String username, String password) {

        public boolean isConfigured() {
            return username != null && !username.isBlank() && password != null && !password.isBlank();
        }

        @Override
        public String toString() {
            return "Credentials[username=%s, password=%s]"
                    .formatted(Masking.secret(username), Masking.secret(password));
        }
    }

    /**
     * How a message is addressed on the Playmobile side.
     *
     * @param originator alpha-name used when the message does not carry one of its own (§9.1)
     * @param organisationPrefix prefix of the {@code message-id}, agreed with Playmobile; the id is
     *     {@code <prefix><number>} and at most 20 characters
     * @param defaultTtl {@code sms.ttl} for a message with no TTL of its own; {@code null} omits the field
     * @param priorities traffic class → Playmobile priority word (PM-03)
     */
    public record Sending(
            String originator, String organisationPrefix, Duration defaultTtl, Map<TrafficClass, String> priorities) {

        public static final String REALTIME = "realtime";
        public static final String HIGH = "high";
        public static final String NORMAL = "normal";
        public static final String LOW = "low";

        /** PM-03 as the spec states it; overridable per environment because the SLA is negotiated. */
        private static final Map<TrafficClass, String> DEFAULT_PRIORITIES = Map.of(
                TrafficClass.CRITICAL_OTP, REALTIME,
                TrafficClass.TRANSACTIONAL, NORMAL,
                TrafficClass.NOTIFICATION, LOW);

        /** Playmobile's own ordering, used to compare the class default with the message priority. */
        private static final Map<String, Integer> RANKS = Map.of(LOW, 0, NORMAL, 1, HIGH, 2, REALTIME, 3);

        public Sending {
            priorities = priorities == null || priorities.isEmpty()
                    ? DEFAULT_PRIORITIES
                    : Map.copyOf(new EnumMap<>(priorities));
        }

        public static Sending defaults() {
            return new Sending(null, null, null, null);
        }

        /**
         * These settings with {@code provider.endpoint_config} applied on top (AD-07, §10.1).
         *
         * <p>Keys: {@code originator}, {@code message-id-prefix}, {@code default-ttl} (ISO-8601) and
         * {@code priority.<traffic-class>}. An absent or unparsable key leaves the configured value in
         * place — a typo in the admin panel must not stop SMS from going out, and the field it would
         * have changed is visible to the operator either way.
         */
        public Sending overlay(Map<String, String> endpointConfig) {
            if (endpointConfig == null || endpointConfig.isEmpty()) {
                return this;
            }
            Map<TrafficClass, String> overlaid = new EnumMap<>(priorities);
            for (TrafficClass trafficClass : TrafficClass.values()) {
                String key = "priority."
                        + trafficClass.name().toLowerCase(Locale.ROOT).replace('_', '-');
                String word = endpointConfig.get(key);
                if (word != null && RANKS.containsKey(word)) {
                    overlaid.put(trafficClass, word);
                }
            }
            return new Sending(
                    endpointConfig.getOrDefault("originator", originator),
                    endpointConfig.getOrDefault("message-id-prefix", organisationPrefix),
                    duration(endpointConfig.get("default-ttl"), defaultTtl),
                    overlaid);
        }

        private static Duration duration(String value, Duration fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Duration.parse(value);
            } catch (java.time.format.DateTimeParseException e) {
                return fallback;
            }
        }

        /**
         * Priority word of one message (PM-03).
         *
         * <p>The traffic class sets the floor and the message may only raise it. A source system that
         * marks a marketing message {@code REALTIME} must not thereby buy itself the OTP lane — the
         * lane belongs to the class, which comes from the topic and not from the document (TC-01).
         */
        public String priorityOf(TrafficClass trafficClass, Priority priority) {
            String fromClass = priorities.getOrDefault(trafficClass, NORMAL);
            String fromMessage = of(priority);
            return rank(fromMessage) > rank(fromClass) && trafficClass != TrafficClass.NOTIFICATION
                    ? fromMessage
                    : fromClass;
        }

        private static String of(Priority priority) {
            return switch (priority) {
                case REALTIME -> REALTIME;
                case HIGH -> HIGH;
                case NORMAL -> NORMAL;
                case LOW -> LOW;
            };
        }

        private static int rank(String priority) {
            return RANKS.getOrDefault(priority, 1);
        }
    }
}
