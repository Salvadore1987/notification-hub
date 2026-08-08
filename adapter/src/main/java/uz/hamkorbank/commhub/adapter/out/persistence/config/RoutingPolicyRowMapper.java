package uz.hamkorbank.commhub.adapter.out.persistence.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RoutingActionJson;
import uz.hamkorbank.commhub.adapter.out.persistence.json.RoutingMatchJson;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;

/** Rebuilds a {@link RoutingPolicy} from a {@code routing_policy} row (FR-8.9). */
@Component
public class RoutingPolicyRowMapper implements RowMapper<RoutingPolicy> {

    private final JsonCodec jsonCodec;

    public RoutingPolicyRowMapper(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    @Override
    public RoutingPolicy mapRow(ResultSet rs, int rowNum) throws SQLException {
        RoutingPolicy policy = RoutingPolicy.of(
                RoutingPolicyId.of(SqlValues.uuid(rs, "id")),
                jsonCodec.read(rs.getString("match"), RoutingMatchJson.class).toDomain(),
                jsonCodec.read(rs.getString("action"), RoutingActionJson.class).toDomain(),
                rs.getInt("priority"));
        if (!rs.getBoolean("enabled")) {
            policy.disable();
        }
        return policy;
    }
}
