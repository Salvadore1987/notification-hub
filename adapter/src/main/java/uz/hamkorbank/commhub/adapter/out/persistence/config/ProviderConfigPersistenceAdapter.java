package uz.hamkorbank.commhub.adapter.out.persistence.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.json.QuietHoursJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RateLimitJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RoutingActionJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RoutingMatchJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.TariffJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;

/**
 * {@link ProviderConfigRepository} over {@code provider}, {@code channel} and {@code routing_policy}
 * (§10.1, FR-2.1…FR-2.3, FR-8.9).
 *
 * <p>{@code channel.fallback_order} stores provider codes, not ids: the admin panel edits the order by
 * code, and a code outlives the row it points at. Rebuilding a {@link ChannelConfig} therefore resolves
 * the codes against the providers of that channel, and a code with no provider behind it is dropped —
 * a deleted provider must not make the whole channel unreadable (FR-2.2).
 */
@Repository
public class ProviderConfigPersistenceAdapter implements ProviderConfigRepository {

    private static final String SELECT_PROVIDER = """
            SELECT id, code, channel, adapter_type, weight, tariff, rate_limit_config, credentials_ref,
                   enabled, maintenance, health_status
            FROM provider
            """;

    private static final String UPSERT_PROVIDER = """
            INSERT INTO provider (id, code, channel, adapter_type, weight, tariff, rate_limit_config,
                                  credentials_ref, enabled, maintenance, health_status)
            VALUES (:id, :code, :channel, :adapterType, :weight, CAST(:tariff AS jsonb),
                    CAST(:rateLimit AS jsonb), :credentialsRef, :enabled, :maintenance, :health)
            ON CONFLICT (id) DO UPDATE SET
                code = EXCLUDED.code,
                channel = EXCLUDED.channel,
                adapter_type = EXCLUDED.adapter_type,
                weight = EXCLUDED.weight,
                tariff = EXCLUDED.tariff,
                rate_limit_config = EXCLUDED.rate_limit_config,
                credentials_ref = EXCLUDED.credentials_ref,
                enabled = EXCLUDED.enabled,
                maintenance = EXCLUDED.maintenance,
                health_status = EXCLUDED.health_status,
                updated_at = now()
            """;

    private static final String SELECT_CHANNEL =
            "SELECT code, status, balancing_strategy, fallback_order, quiet_hours FROM channel";

    private static final String UPSERT_CHANNEL = """
            INSERT INTO channel (code, status, balancing_strategy, fallback_order, quiet_hours)
            VALUES (:code, :status, :strategy, CAST(:fallbackOrder AS jsonb), CAST(:quietHours AS jsonb))
            ON CONFLICT (code) DO UPDATE SET
                status = EXCLUDED.status,
                balancing_strategy = EXCLUDED.balancing_strategy,
                fallback_order = EXCLUDED.fallback_order,
                quiet_hours = EXCLUDED.quiet_hours,
                updated_at = now()
            """;

    private static final String SELECT_POLICY =
            "SELECT id, scope, match, action, priority, enabled FROM routing_policy";

    private static final String UPSERT_POLICY = """
            INSERT INTO routing_policy (id, scope, match, action, priority, enabled)
            VALUES (:id, :scope, CAST(:match AS jsonb), CAST(:action AS jsonb), :priority, :enabled)
            ON CONFLICT (id) DO UPDATE SET
                scope = EXCLUDED.scope,
                match = EXCLUDED.match,
                action = EXCLUDED.action,
                priority = EXCLUDED.priority,
                enabled = EXCLUDED.enabled,
                updated_at = now()
            """;

    private final JdbcClient jdbcClient;
    private final JsonCodec jsonCodec;
    private final ProviderRowMapper providerRowMapper;
    private final RoutingPolicyRowMapper policyRowMapper;
    private final StreamRepository streamRepository;

    public ProviderConfigPersistenceAdapter(
            JdbcClient jdbcClient,
            JsonCodec jsonCodec,
            ProviderRowMapper providerRowMapper,
            RoutingPolicyRowMapper policyRowMapper,
            StreamRepository streamRepository) {
        this.jdbcClient = jdbcClient;
        this.jsonCodec = jsonCodec;
        this.providerRowMapper = providerRowMapper;
        this.policyRowMapper = policyRowMapper;
        this.streamRepository = streamRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public RoutingConfiguration routingConfiguration(StreamId streamId) {
        List<Provider> providers = allProviders();
        Stream.Defaults defaults =
                streamRepository.findById(streamId).map(Stream::defaults).orElseGet(Stream.Defaults::none);
        return RoutingConfiguration.of(channels(providers), providers, enabledPolicies())
                .withStreamDefaults(defaults);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChannelConfig> findChannel(Channel channel) {
        List<Provider> providers = findProviders(channel);
        return jdbcClient
                .sql(SELECT_CHANNEL + " WHERE code = :code")
                .param("code", channel.name())
                .query((rs, rowNum) -> toChannelConfig(rs, refsByCode(providers)))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Provider> findProvider(ProviderId providerId) {
        return jdbcClient
                .sql(SELECT_PROVIDER + " WHERE id = :id")
                .param("id", providerId.value())
                .query(providerRowMapper)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Provider> findProviderByCode(ProviderCode providerCode) {
        return jdbcClient
                .sql(SELECT_PROVIDER + " WHERE code = :code")
                .param("code", providerCode.value())
                .query(providerRowMapper)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Provider> findProviders(Channel channel) {
        return jdbcClient
                .sql(SELECT_PROVIDER + " WHERE channel = :channel ORDER BY code")
                .param("channel", channel.name())
                .query(providerRowMapper)
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoutingPolicy> findPolicies() {
        return jdbcClient
                .sql(SELECT_POLICY + " ORDER BY priority DESC, id")
                .query(policyRowMapper)
                .list();
    }

    @Override
    @Transactional
    public Provider save(Provider provider) {
        jdbcClient
                .sql(UPSERT_PROVIDER)
                .param("id", provider.id().value())
                .param("code", provider.code().value())
                .param("channel", provider.channel().name())
                .param("adapterType", provider.adapterType().value())
                .param("weight", provider.weight())
                .param("tariff", jsonCodec.write(TariffJson.of(provider.tariff().orElse(null))))
                .param("rateLimit", jsonCodec.write(RateLimitJson.of(provider.rateLimit())))
                .param("credentialsRef", provider.credentialsRef().orElse(null))
                .param("enabled", provider.isEnabled())
                .param("maintenance", provider.isInMaintenance())
                .param("health", provider.health().name())
                .update();
        return provider;
    }

    @Override
    @Transactional
    public ChannelConfig save(ChannelConfig channelConfig) {
        List<String> fallbackCodes = channelConfig.fallbackOrder().stream()
                .map(ref -> ref.code().value())
                .toList();
        jdbcClient
                .sql(UPSERT_CHANNEL)
                .param("code", channelConfig.channel().name())
                .param("status", channelConfig.status().name())
                .param("strategy", channelConfig.balancingStrategy().name())
                .param("fallbackOrder", jsonCodec.write(fallbackCodes))
                .param(
                        "quietHours",
                        jsonCodec.write(
                                QuietHoursJson.of(channelConfig.quietHours().orElse(null))))
                .update();
        return channelConfig;
    }

    @Override
    @Transactional
    public RoutingPolicy save(RoutingPolicy policy) {
        jdbcClient
                .sql(UPSERT_POLICY)
                .param("id", policy.id().value())
                .param("scope", scopeOf(policy.match()))
                .param("match", jsonCodec.write(RoutingMatchJson.of(policy.match())))
                .param("action", jsonCodec.write(RoutingActionJson.of(policy.action())))
                .param("priority", policy.priority())
                .param("enabled", policy.isEnabled())
                .update();
        return policy;
    }

    private List<Provider> allProviders() {
        return jdbcClient
                .sql(SELECT_PROVIDER + " ORDER BY code")
                .query(providerRowMapper)
                .list();
    }

    private List<RoutingPolicy> enabledPolicies() {
        return jdbcClient
                .sql(SELECT_POLICY + " WHERE enabled ORDER BY priority DESC, id")
                .query(policyRowMapper)
                .list();
    }

    private Map<Channel, ChannelConfig> channels(List<Provider> providers) {
        Map<ProviderCode, ProviderRef> refs = refsByCode(providers);
        Map<Channel, ChannelConfig> configs = new EnumMap<>(Channel.class);
        jdbcClient
                .sql(SELECT_CHANNEL)
                .query((rs, rowNum) -> toChannelConfig(rs, refs))
                .list()
                .forEach(config -> configs.put(config.channel(), config));
        return configs;
    }

    private Map<ProviderCode, ProviderRef> refsByCode(List<Provider> providers) {
        Map<ProviderCode, ProviderRef> refs = new LinkedHashMap<>();
        providers.forEach(provider -> refs.put(provider.code(), provider.ref()));
        return refs;
    }

    private ChannelConfig toChannelConfig(ResultSet rs, Map<ProviderCode, ProviderRef> refs) throws SQLException {
        Channel channel = Channel.valueOf(rs.getString("code"));
        ChannelConfig config =
                ChannelConfig.of(channel, SqlValues.enumValue(rs, "balancing_strategy", BalancingStrategy.class));
        applyStatus(config, SqlValues.enumValue(rs, "status", ChannelStatus.class));
        config.updateFallbackOrder(jsonCodec.readList(rs.getString("fallback_order"), String.class).stream()
                .map(ProviderCode::of)
                .map(refs::get)
                .filter(Objects::nonNull)
                .toList());
        config.updateQuietHours(
                QuietHoursJson.toDomain(jsonCodec.read(rs.getString("quiet_hours"), QuietHoursJson.class)));
        return config;
    }

    private void applyStatus(ChannelConfig config, ChannelStatus status) {
        switch (status) {
            case ACTIVE -> config.activate();
            case MAINTENANCE -> config.enterMaintenance();
            case DISABLED -> config.disable();
            default -> throw new IllegalStateException("unknown channel status " + status);
        }
    }

    /** Scope of a policy is derived from its match and stored denormalized for admin filters (§10.1). */
    private String scopeOf(RoutingPolicy.Match match) {
        if (match.streamId() != null) {
            return "STREAM";
        }
        if (match.channel() != null) {
            return "CHANNEL";
        }
        return match.trafficClass() != null ? "TRAFFIC_CLASS" : "GLOBAL";
    }
}
