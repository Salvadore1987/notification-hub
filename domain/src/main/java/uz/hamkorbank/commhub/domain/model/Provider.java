package uz.hamkorbank.commhub.domain.model;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Integration profile of one delivery provider (§6.1, FR-2.1, FR-2.5, FR-2.7).
 *
 * <p>Credentials never live in the domain: {@link #credentialsRef()} is a reference resolved through
 * {@code SecretResolverPort} against Vault/K8s secrets (SEC-04, SG-04). The adapter behind the profile
 * is identified by {@link AdapterType}, so a new provider is a new adapter only (AR-04).
 */
public final class Provider extends AggregateRoot<ProviderId> {

    public static final int DEFAULT_WEIGHT = 10;
    public static final int MAX_WEIGHT = 100;

    private final ProviderCode code;
    private final Channel channel;
    private final AdapterType adapterType;

    private int weight;
    private Tariff tariff;
    private RateLimit rateLimit;
    private String credentialsRef;
    private boolean enabled;
    private boolean maintenance;
    private ProviderHealthStatus health;

    private Provider(ProviderId id, ProviderCode code, Channel channel, AdapterType adapterType, Settings settings) {
        super(id);
        this.code = Guard.notNull(code, "Provider.code");
        this.channel = Guard.notNull(channel, "Provider.channel");
        this.adapterType = Guard.notNull(adapterType, "Provider.adapterType");
        Guard.notNull(settings, "Provider.settings");
        this.weight = settings.weight();
        this.tariff = settings.tariff();
        this.rateLimit = settings.rateLimit() == null ? RateLimit.unlimited() : settings.rateLimit();
        this.credentialsRef = settings.credentialsRef();
        this.enabled = settings.enabled();
        this.health = ProviderHealthStatus.UNKNOWN;
    }

    public static Provider register(
            ProviderId id, ProviderCode code, Channel channel, AdapterType adapterType, Settings settings) {
        return new Provider(id, code, channel, adapterType, settings);
    }

    /** Reference the router hands to the application layer (MP-05). */
    public ProviderRef ref() {
        return new ProviderRef(id(), code, channel, adapterType);
    }

    /** Switching a provider on and off is applied without a restart (FR-2.7, AD-07). */
    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    /** Technical maintenance mode: excluded from routing but not disabled (FR-2.7). */
    public void enterMaintenance() {
        this.maintenance = true;
    }

    public void leaveMaintenance() {
        this.maintenance = false;
    }

    /** Health from probes and passive metrics; {@code DOWN} triggers failover (PR-02, FR-6.3). */
    public void markHealth(ProviderHealthStatus newHealth) {
        this.health = Guard.notNull(newHealth, "newHealth");
    }

    public void updateWeight(int newWeight) {
        this.weight = validWeight(newWeight);
    }

    public void updateTariff(Tariff newTariff) {
        this.tariff = newTariff;
    }

    public void updateRateLimit(RateLimit newRateLimit) {
        this.rateLimit = Guard.notNull(newRateLimit, "newRateLimit");
    }

    public void updateCredentialsRef(String newCredentialsRef) {
        this.credentialsRef = newCredentialsRef;
    }

    /** Whether the router may select this provider (FR-2.2, FR-2.7, FR-6.3). */
    public boolean isSelectable() {
        return enabled && !maintenance && health.selectable();
    }

    /** Expected cost of a message with the given segment count; empty when no tariff is set (FR-6.2). */
    public Optional<Money> costOf(int segments) {
        return Optional.ofNullable(tariff).map(configured -> configured.costOf(segments));
    }

    public ProviderCode code() {
        return code;
    }

    public Channel channel() {
        return channel;
    }

    public AdapterType adapterType() {
        return adapterType;
    }

    public int weight() {
        return weight;
    }

    public Optional<Tariff> tariff() {
        return Optional.ofNullable(tariff);
    }

    public RateLimit rateLimit() {
        return rateLimit;
    }

    public Optional<String> credentialsRef() {
        return Optional.ofNullable(credentialsRef);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInMaintenance() {
        return maintenance;
    }

    public ProviderHealthStatus health() {
        return health;
    }

    private static int validWeight(int weight) {
        Guard.positive(weight, "Provider.weight");
        Guard.isTrue(weight <= MAX_WEIGHT, "Provider.weight must not exceed " + MAX_WEIGHT);
        return weight;
    }

    /**
     * Mutable part of a provider profile (§10.1 {@code provider}).
     *
     * @param weight relative share for weighted balancing (FR-2.3)
     * @param tariff price list; {@code null} when cost is not tracked for the provider
     * @param rateLimit throughput limits; {@code null} means unlimited
     * @param credentialsRef reference to the credentials in the secret store (SEC-04)
     */
    public record Settings(int weight, Tariff tariff, RateLimit rateLimit, String credentialsRef, boolean enabled) {

        public Settings {
            weight = validWeight(weight);
        }

        /** Enabled provider with the default weight, no tariff and no rate limit. */
        public static Settings defaults() {
            return new Settings(DEFAULT_WEIGHT, null, RateLimit.unlimited(), null, true);
        }

        public Settings withTariff(Tariff newTariff) {
            return new Settings(weight, newTariff, rateLimit, credentialsRef, enabled);
        }

        public Settings withWeight(int newWeight) {
            return new Settings(newWeight, tariff, rateLimit, credentialsRef, enabled);
        }

        public Settings withRateLimit(RateLimit newRateLimit) {
            return new Settings(weight, tariff, newRateLimit, credentialsRef, enabled);
        }

        public Settings withCredentialsRef(String newCredentialsRef) {
            return new Settings(weight, tariff, rateLimit, newCredentialsRef, enabled);
        }
    }
}
