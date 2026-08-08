package uz.hamkorbank.commhub.adapter.out.persistence.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RateLimitJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.TariffJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;

/**
 * Rebuilds a {@link Provider} from a {@code provider} row (FR-2.1, AR-04).
 *
 * <p>Availability is restored in two steps because the domain keeps the reasons apart: {@code enabled}
 * is the administrative decision, {@code maintenance} a temporary window, and {@code health} the
 * verdict of the health check — only all three together make a provider selectable (FR-2.7, PR-02).
 */
@Component
public class ProviderRowMapper implements RowMapper<Provider> {

    private final JsonCodec jsonCodec;

    public ProviderRowMapper(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Provider mapRow(ResultSet rs, int rowNum) throws SQLException {
        Provider.Settings settings = new Provider.Settings(
                rs.getInt("weight"),
                TariffJson.toDomain(jsonCodec.read(rs.getString("tariff"), TariffJson.class)),
                RateLimitJson.toDomain(jsonCodec.read(rs.getString("rate_limit_config"), RateLimitJson.class)),
                rs.getString("credentials_ref"),
                rs.getBoolean("enabled"));
        Provider provider = Provider.register(
                ProviderId.of(SqlValues.uuid(rs, "id")),
                ProviderCode.of(rs.getString("code")),
                SqlValues.enumValue(rs, "channel", Channel.class),
                AdapterType.of(rs.getString("adapter_type")),
                settings);
        if (rs.getBoolean("maintenance")) {
            provider.enterMaintenance();
        }
        provider.markHealth(SqlValues.enumValue(rs, "health_status", ProviderHealthStatus.class));
        return provider;
    }
}
