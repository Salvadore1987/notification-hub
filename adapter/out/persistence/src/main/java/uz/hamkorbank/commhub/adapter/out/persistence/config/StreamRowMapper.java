package uz.hamkorbank.commhub.adapter.out.persistence.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.json.QuietHoursJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.QuotaConfigJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RateLimitJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * Rebuilds a {@link Stream} from a {@code stream} row joined with its default provider.
 *
 * <p>The aggregate is restored through its own API: {@code register} plus the update methods. No
 * rehydration builder is needed here — a stream has no status machine, its state is whatever the
 * admin panel last set (FR-1.3, FR-2.4).
 */
@Component
public class StreamRowMapper implements RowMapper<Stream> {

    private final JsonCodec jsonCodec;

    public StreamRowMapper(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Stream mapRow(ResultSet rs, int rowNum) throws SQLException {
        Stream stream = Stream.register(
                StreamId.of(rs.getString("id")),
                rs.getString("name"),
                SqlValues.enumValue(rs, "integration_type", IntegrationType.class),
                defaults(rs));
        applyStatus(stream, SqlValues.enumValue(rs, "status", StreamStatus.class));
        stream.updateQuota(
                QuotaConfigJson.toDomain(jsonCodec.read(rs.getString("quota_config"), QuotaConfigJson.class)));
        stream.updateRateLimit(
                RateLimitJson.toDomain(jsonCodec.read(rs.getString("rate_limit_config"), RateLimitJson.class)));
        stream.updateQuietHours(
                QuietHoursJson.toDomain(jsonCodec.read(rs.getString("quiet_hours"), QuietHoursJson.class)));
        if (SqlValues.instant(rs, "last_activity_at") != null) {
            stream.touch(SqlValues.instant(rs, "last_activity_at"));
        }
        return stream;
    }

    private Stream.Defaults defaults(ResultSet rs) throws SQLException {
        return new Stream.Defaults(
                SqlValues.enumValue(rs, "default_channel", Channel.class),
                defaultProvider(rs),
                SqlValues.enumValue(rs, "default_traffic_class", TrafficClass.class),
                SqlValues.enumValue(rs, "default_priority", Priority.class),
                SqlValues.enumValue(rs, "default_balancing_strategy", BalancingStrategy.class));
    }

    private ProviderRef defaultProvider(ResultSet rs) throws SQLException {
        UUID providerId = SqlValues.uuid(rs, "default_provider_id");
        if (providerId == null) {
            return null;
        }
        return new ProviderRef(
                ProviderId.of(providerId),
                ProviderCode.of(rs.getString("default_provider_code")),
                SqlValues.enumValue(rs, "default_provider_channel", Channel.class),
                AdapterType.of(rs.getString("default_provider_adapter_type")));
    }

    private void applyStatus(Stream stream, StreamStatus status) {
        switch (status) {
            case ACTIVE -> stream.activate();
            case SUSPENDED -> stream.suspend();
            case DISABLED -> stream.disable();
            default -> throw new IllegalStateException("unknown stream status " + status);
        }
    }
}
