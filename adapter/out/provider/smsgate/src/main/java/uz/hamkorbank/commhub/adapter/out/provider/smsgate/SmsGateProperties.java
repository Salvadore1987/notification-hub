package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/**
 * Settings of the SMS Gate v4.3 integration (§9.2, SG-01…SG-04).
 *
 * <p>The reserve SMS provider of the MVP. Its API has neither templates nor a send window, so the Hub
 * compensates: the text arrives already rendered and a deferred send is held by the Hub's own scheduler
 * (SG-01). Nothing about that is visible here — it is simply what the adapter does not send.
 *
 * <p>The anti-spam ceiling of 50 SMS per hour per number (§18.2) is the default of
 * {@link #rateLimit()}, set slightly below the provider's own: the limiter counts per instance, so the
 * cluster as a whole must aim under the line rather than at it. Since Phase 8 the limit stored on the
 * {@code provider} row wins over it when there is one, so it can be tightened without a restart
 * (FR-2.5, AD-07); the sender name and the weights are overlaid from {@code provider.endpoint_config}
 * the same way.
 */
@ConfigurationProperties("commhub.provider.smsgate")
public record SmsGateProperties(
        Boolean enabled,
        String providerCode,
        Credentials credentials,
        Sending sending,
        Reconciliation reconciliation,
        RateLimit rateLimit,
        ProviderHttpProperties http,
        ProviderResilienceProperties resilience) {

    public static final String DEFAULT_PROVIDER_CODE = "SMSGATE";

    /** Single send, answering with {@code id} and {@code parts} (§9.2). */
    public static final String SEND_PATH = "/api/v2/send";

    /** Bulk send: {@code sender}, {@code weight}, {@code messages[{seq, phone, text}]} (§9.2). */
    public static final String SEND_BATCH_PATH = "/api/v2/send_msgs";

    /** Status lookup by number and date, used for reconciliation (SG-03, §9.2). */
    public static final String SEARCH_PATH = "/api/v2/search";

    public SmsGateProperties {
        enabled = enabled != null && enabled;
        providerCode = providerCode == null || providerCode.isBlank()
                ? DEFAULT_PROVIDER_CODE
                : providerCode.trim().toUpperCase(Locale.ROOT);
        credentials = credentials == null ? new Credentials(null, null) : credentials;
        sending = sending == null ? Sending.defaults() : sending;
        reconciliation = reconciliation == null ? Reconciliation.defaults() : reconciliation;
        rateLimit = rateLimit == null ? Sending.antiSpamDefault() : rateLimit;
        http = http == null ? ProviderHttpProperties.of(null) : http;
        // No retry inside a delivery attempt: /api/v2/send takes no client-supplied identifier, so a
        // request that arrived but was not acknowledged cannot be recognised as a duplicate — retrying
        // it would send the customer a second SMS (§9.2). The saga's failover covers the case instead.
        resilience = resilience == null ? ProviderResilienceProperties.withoutInnerRetry() : resilience;
    }

    public static SmsGateProperties defaults() {
        return new SmsGateProperties(null, null, null, null, null, null, null, null);
    }

    /**
     * {@code login} and {@code key}, which travel in every request (§9.2, SG-04).
     *
     * <p>Values filled from the environment of the pod (ADR-0044), so {@code toString} is masked: a
     * record prints its components, and this one is part of a properties tree something may log whole.
     */
    public record Credentials(String login, String key) {

        public boolean isConfigured() {
            return login != null && !login.isBlank() && key != null && !key.isBlank();
        }

        @Override
        public String toString() {
            return "Credentials[login=%s, key=%s]".formatted(Masking.secret(login), Masking.secret(key));
        }
    }

    /**
     * How a message is addressed on the SMS Gate side.
     *
     * @param sender registered sender name used when the message carries none; SMS Gate refuses an
     *     unregistered one with code 16 (§18.2)
     * @param weights traffic class → {@code weight} 0–10, higher meaning sent earlier (§9.2)
     */
    public record Sending(String sender, Map<TrafficClass, Integer> weights) {

        public static final int MAX_WEIGHT = 10;

        /** §18.2 anti-spam rule, aimed just under the provider's own 50 per hour per number. */
        public static final int RECIPIENT_MESSAGES_PER_HOUR = 45;

        private static final Map<TrafficClass, Integer> DEFAULT_WEIGHTS = Map.of(
                TrafficClass.CRITICAL_OTP, MAX_WEIGHT,
                TrafficClass.TRANSACTIONAL, 7,
                TrafficClass.NOTIFICATION, 3);

        public Sending {
            weights = weights == null || weights.isEmpty() ? DEFAULT_WEIGHTS : Map.copyOf(new EnumMap<>(weights));
        }

        public static Sending defaults() {
            return new Sending(null, null);
        }

        /**
         * These settings with {@code provider.endpoint_config} applied on top (AD-07, §10.1).
         *
         * <p>Keys: {@code sender} and {@code weight.<traffic-class>}. A value that is not a number, or
         * outside 0–10, is ignored rather than clamped silently — an operator who typed 100 meant
         * something, and the configured weight is the safer guess at what.
         */
        public Sending overlay(Map<String, String> endpointConfig) {
            if (endpointConfig == null || endpointConfig.isEmpty()) {
                return this;
            }
            Map<TrafficClass, Integer> overlaid = new EnumMap<>(weights);
            for (TrafficClass trafficClass : TrafficClass.values()) {
                String key =
                        "weight." + trafficClass.name().toLowerCase(Locale.ROOT).replace('_', '-');
                weight(endpointConfig.get(key)).ifPresent(value -> overlaid.put(trafficClass, value));
            }
            return new Sending(endpointConfig.getOrDefault("sender", sender), overlaid);
        }

        private static java.util.Optional<Integer> weight(String value) {
            if (value == null || value.isBlank()) {
                return java.util.Optional.empty();
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                return parsed >= 0 && parsed <= MAX_WEIGHT ? java.util.Optional.of(parsed) : java.util.Optional.empty();
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        }

        public static RateLimit antiSpamDefault() {
            return new RateLimit(0, 0, RECIPIENT_MESSAGES_PER_HOUR);
        }

        /** {@code weight} of one message, clamped to the range the API accepts (§9.2). */
        public int weightOf(TrafficClass trafficClass) {
            int weight = weights.getOrDefault(trafficClass, MAX_WEIGHT);
            return Math.max(0, Math.min(MAX_WEIGHT, weight));
        }
    }

    /**
     * Polling of {@code /api/v2/search} for messages whose delivery report never arrived (SG-03).
     *
     * @param enabled whether the job runs; off where the FEEDBACK webhook is known to be reliable
     * @param after how long a message may sit in {@code SENT_TO_PROVIDER} before it is looked up
     * @param batchSize messages inspected per pass; each one is a separate provider call, so this
     *     bounds what a single pass costs the provider
     * @param interval delay between passes
     */
    public record Reconciliation(Boolean enabled, Duration after, Integer batchSize, Duration interval) {

        public static final Duration DEFAULT_AFTER = Duration.ofMinutes(15);
        public static final int DEFAULT_BATCH_SIZE = 100;
        public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

        public Reconciliation {
            enabled = enabled != null && enabled;
            after = after == null || after.isNegative() || after.isZero() ? DEFAULT_AFTER : after;
            batchSize = batchSize == null || batchSize < 1 ? DEFAULT_BATCH_SIZE : batchSize;
            interval = interval == null || interval.isNegative() || interval.isZero() ? DEFAULT_INTERVAL : interval;
        }

        public static Reconciliation defaults() {
            return new Reconciliation(null, null, null, null);
        }
    }
}
