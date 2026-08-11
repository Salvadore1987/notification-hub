package uz.hamkorbank.commhub.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ConnectionStatus;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * An inbound stream: one registered source system (§6.1, FR-1.3, §18.4).
 *
 * <p>Holds the integration type, the default channel/provider/traffic class applied when a submission
 * does not name them (FR-2.4, TC-02), the quotas (FR-2.6), the quiet hours (FR-5.3) and the connection
 * liveness derived from the last observed activity (FR-1.3).
 */
public final class Stream extends AggregateRoot<StreamId> {

    /** No traffic for this long means the source system is considered idle (FR-1.3). */
    public static final Duration DEFAULT_IDLE_THRESHOLD = Duration.ofMinutes(15);

    /** No traffic for this long means the source system is considered disconnected (FR-1.3). */
    public static final Duration DEFAULT_DISCONNECTED_THRESHOLD = Duration.ofHours(24);

    public static final int MAX_NAME_LENGTH = 128;

    private final String name;
    private final IntegrationType integrationType;

    private Defaults defaults;
    private QuotaConfig quota;
    private RateLimit rateLimit;
    private QuietHours quietHours;
    private StreamStatus status;
    private Instant lastActivityAt;

    private Stream(StreamId id, String name, IntegrationType integrationType, Defaults defaults) {
        super(id);
        this.name = Guard.maxLength(Guard.notBlank(name, "Stream.name"), MAX_NAME_LENGTH, "Stream.name");
        this.integrationType = Guard.notNull(integrationType, "Stream.integrationType");
        this.defaults = Guard.notNull(defaults, "Stream.defaults");
        this.quota = QuotaConfig.unlimited();
        this.rateLimit = RateLimit.unlimited();
        this.status = StreamStatus.ACTIVE;
    }

    /** Registers a source system; it starts {@code ACTIVE} with unlimited quotas (FR-1.3). */
    public static Stream register(StreamId id, String name, IntegrationType integrationType, Defaults defaults) {
        return new Stream(id, name, integrationType, defaults);
    }

    public void activate() {
        this.status = StreamStatus.ACTIVE;
    }

    /** Suspends the stream: submissions are rejected with {@code STREAM_SUSPENDED} (FR-3.2, IR-01). */
    public void suspend() {
        this.status = StreamStatus.SUSPENDED;
    }

    public void disable() {
        this.status = StreamStatus.DISABLED;
    }

    public void updateDefaults(Defaults newDefaults) {
        this.defaults = Guard.notNull(newDefaults, "newDefaults");
    }

    public void updateQuota(QuotaConfig newQuota) {
        this.quota = Guard.notNull(newQuota, "newQuota");
    }

    /**
     * Request rate this source system may submit at (IR-02).
     *
     * <p>Counted in requests rather than messages — a batch chunk is one request — and enforced by the
     * synchronous API adapter; a Kafka stream is paced by its partitions and its consumer group.
     */
    public void updateRateLimit(RateLimit newRateLimit) {
        this.rateLimit = Guard.notNull(newRateLimit, "newRateLimit");
    }

    public void updateQuietHours(QuietHours newQuietHours) {
        this.quietHours = newQuietHours;
    }

    /** Records activity of the source system; drives the connection status (FR-1.3). */
    public void touch(Instant activityAt) {
        Guard.notNull(activityAt, "activityAt");
        if (lastActivityAt == null || activityAt.isAfter(lastActivityAt)) {
            this.lastActivityAt = activityAt;
        }
    }

    public boolean isAcceptingTraffic() {
        return status.acceptsTraffic();
    }

    /** Traffic class of a submission: explicit value wins, otherwise the stream default (TC-02). */
    public TrafficClass effectiveTrafficClass(TrafficClass requested) {
        if (requested != null) {
            return requested;
        }
        return defaults.trafficClass() == null ? TrafficClass.NOTIFICATION : defaults.trafficClass();
    }

    /** Channel of a submission: explicit value wins, otherwise the stream default (FR-2.4). */
    public Optional<Channel> effectiveChannel(Channel requested) {
        return requested != null ? Optional.of(requested) : defaults.channelOptional();
    }

    /** Priority of a submission: explicit value wins, then the stream default, then the class default. */
    public Priority effectivePriority(Priority requested, TrafficClass trafficClass) {
        if (requested != null) {
            return requested;
        }
        if (defaults.priority() != null) {
            return defaults.priority();
        }
        return trafficClass.defaultPriority();
    }

    /** Liveness derived from the last activity with the default thresholds (FR-1.3). */
    public ConnectionStatus connectionStatus(Instant now) {
        return connectionStatus(now, DEFAULT_IDLE_THRESHOLD, DEFAULT_DISCONNECTED_THRESHOLD);
    }

    /** Liveness derived from the last activity (FR-1.3). */
    public ConnectionStatus connectionStatus(Instant now, Duration idleAfter, Duration disconnectedAfter) {
        Guard.notNull(now, "now");
        Guard.notNull(idleAfter, "idleAfter");
        Guard.notNull(disconnectedAfter, "disconnectedAfter");
        if (lastActivityAt == null) {
            return ConnectionStatus.UNKNOWN;
        }
        Duration silence = Duration.between(lastActivityAt, now);
        if (silence.compareTo(disconnectedAfter) >= 0) {
            return ConnectionStatus.DISCONNECTED;
        }
        if (silence.compareTo(idleAfter) >= 0) {
            return ConnectionStatus.IDLE;
        }
        return ConnectionStatus.CONNECTED;
    }

    public String name() {
        return name;
    }

    public IntegrationType integrationType() {
        return integrationType;
    }

    public Defaults defaults() {
        return defaults;
    }

    public QuotaConfig quota() {
        return quota;
    }

    public RateLimit rateLimit() {
        return rateLimit;
    }

    public Optional<QuietHours> quietHours() {
        return Optional.ofNullable(quietHours);
    }

    public StreamStatus status() {
        return status;
    }

    public Optional<Instant> lastActivityAt() {
        return Optional.ofNullable(lastActivityAt);
    }

    /**
     * Defaults applied to submissions of this stream (FR-2.4, FR-2.3, TC-02); every field is optional.
     *
     * @param provider default provider; {@code null} leaves the choice to the router
     * @param balancingStrategy strategy this stream's traffic is balanced with; {@code null} keeps the
     *     strategy of the channel (FR-2.3)
     */
    public record Defaults(
            Channel channel,
            ProviderRef provider,
            TrafficClass trafficClass,
            Priority priority,
            BalancingStrategy balancingStrategy) {

        public Defaults {
            if (channel != null && provider != null) {
                Guard.isTrue(
                        provider.channel() == channel,
                        "default provider %s does not serve default channel %s".formatted(provider.code(), channel));
            }
        }

        public static Defaults none() {
            return new Defaults(null, null, null, null, null);
        }

        public static Defaults of(Channel channel, TrafficClass trafficClass) {
            return new Defaults(channel, null, trafficClass, null, null);
        }

        public Optional<Channel> channelOptional() {
            return Optional.ofNullable(channel);
        }

        public Optional<ProviderRef> providerOptional() {
            return Optional.ofNullable(provider);
        }

        /** Strategy override of the stream; empty leaves the channel strategy in charge (FR-2.3). */
        public Optional<BalancingStrategy> balancingStrategyOptional() {
            return Optional.ofNullable(balancingStrategy);
        }

        public Defaults withBalancingStrategy(BalancingStrategy strategy) {
            return new Defaults(channel, provider, trafficClass, priority, strategy);
        }
    }
}
