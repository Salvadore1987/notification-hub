package uz.hamkorbank.commhub.adapter.out.persistence.config;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.json.QuietHoursJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.QuotaConfigJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RateLimitJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** {@link StreamRepository} over the {@code stream} table (§10.1, FR-1.3). */
@Repository
public class StreamPersistenceAdapter implements StreamRepository {

    private static final String SELECT = """
            SELECT s.id, s.name, s.integration_type, s.status, s.default_channel, s.default_provider_id,
                   s.default_traffic_class, s.default_priority, s.default_balancing_strategy,
                   s.quota_config, s.rate_limit_config, s.quiet_hours,
                   s.credentials_ref, s.last_activity_at,
                   p.code AS default_provider_code,
                   p.channel AS default_provider_channel,
                   p.adapter_type AS default_provider_adapter_type
            FROM stream s
            LEFT JOIN provider p ON p.id = s.default_provider_id
            """;

    private static final String UPSERT = """
            INSERT INTO stream (id, name, integration_type, status, default_channel, default_provider_id,
                                default_traffic_class, default_priority, default_balancing_strategy,
                                quota_config, rate_limit_config, quiet_hours,
                                credentials_ref, last_activity_at)
            VALUES (:id, :name, :integrationType, :status, :defaultChannel, :defaultProviderId,
                    :defaultTrafficClass, :defaultPriority, :defaultStrategy, CAST(:quotaConfig AS jsonb),
                    CAST(:rateLimit AS jsonb), CAST(:quietHours AS jsonb), :credentialsRef, :lastActivityAt)
            ON CONFLICT (id) DO UPDATE SET
                name = EXCLUDED.name,
                integration_type = EXCLUDED.integration_type,
                status = EXCLUDED.status,
                default_channel = EXCLUDED.default_channel,
                default_provider_id = EXCLUDED.default_provider_id,
                default_traffic_class = EXCLUDED.default_traffic_class,
                default_priority = EXCLUDED.default_priority,
                default_balancing_strategy = EXCLUDED.default_balancing_strategy,
                quota_config = EXCLUDED.quota_config,
                rate_limit_config = EXCLUDED.rate_limit_config,
                quiet_hours = EXCLUDED.quiet_hours,
                credentials_ref = EXCLUDED.credentials_ref,
                last_activity_at = EXCLUDED.last_activity_at,
                updated_at = now()
            """;

    private final JdbcClient jdbcClient;
    private final JsonCodec jsonCodec;
    private final StreamRowMapper rowMapper;

    public StreamPersistenceAdapter(JdbcClient jdbcClient, JsonCodec jsonCodec, StreamRowMapper rowMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonCodec = jsonCodec;
        this.rowMapper = rowMapper;
    }

    @Override
    @Transactional
    public Stream save(Stream stream) {
        jdbcClient
                .sql(UPSERT)
                .param("id", stream.id().value())
                .param("name", stream.name())
                .param("integrationType", stream.integrationType().name())
                .param("status", stream.status().name())
                .param("defaultChannel", SqlValues.nameOf(stream.defaults().channel()))
                .param(
                        "defaultProviderId",
                        stream.defaults()
                                .providerOptional()
                                .map(ProviderRef::id)
                                .map(providerId -> providerId.value())
                                .orElse(null))
                .param("defaultTrafficClass", SqlValues.nameOf(stream.defaults().trafficClass()))
                .param("defaultPriority", SqlValues.nameOf(stream.defaults().priority()))
                .param("defaultStrategy", SqlValues.nameOf(stream.defaults().balancingStrategy()))
                .param("quotaConfig", jsonCodec.write(QuotaConfigJson.of(stream.quota())))
                .param("rateLimit", jsonCodec.write(RateLimitJson.of(stream.rateLimit())))
                .param(
                        "quietHours",
                        jsonCodec.write(QuietHoursJson.of(stream.quietHours().orElse(null))))
                .param("credentialsRef", stream.credentialsRef().orElse(null))
                .param(
                        "lastActivityAt",
                        SqlValues.timestamp(stream.lastActivityAt().orElse(null)))
                .update();
        return stream;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Stream> findById(StreamId streamId) {
        return jdbcClient
                .sql(SELECT + " WHERE s.id = :id")
                .param("id", streamId.value())
                .query(rowMapper)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stream> findAll() {
        return jdbcClient.sql(SELECT + " ORDER BY s.id").query(rowMapper).list();
    }
}
