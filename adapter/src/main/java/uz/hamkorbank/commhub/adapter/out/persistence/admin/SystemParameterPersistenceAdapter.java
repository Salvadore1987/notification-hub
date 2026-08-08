package uz.hamkorbank.commhub.adapter.out.persistence.admin;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.SystemParameter;
import uz.hamkorbank.commhub.application.port.out.SystemParameterPort;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * {@link SystemParameterPort} over {@code system_parameter} (§11.2 "Администрирование", NF-06).
 *
 * <p>Not cached, unlike the kill switch next to it, and the difference is who reads it: the switch is
 * read on the OTP path for every message, these are read by an administration screen. A cache here would
 * add staleness to the one screen whose whole job is showing what the values currently are.
 */
@Repository
public class SystemParameterPersistenceAdapter implements SystemParameterPort {

    private static final String SELECT = "SELECT key, value, description, updated_at, updated_by FROM system_parameter";

    private static final String UPSERT = """
            INSERT INTO system_parameter (key, value, description, updated_at, updated_by)
            VALUES (:key, :value, :description, :updatedAt, :updatedBy)
            ON CONFLICT (key) DO UPDATE SET
                value = EXCLUDED.value,
                description = EXCLUDED.description,
                updated_at = EXCLUDED.updated_at,
                updated_by = EXCLUDED.updated_by
            """;

    private final JdbcClient jdbcClient;

    public SystemParameterPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = Guard.notNull(jdbcClient, "jdbcClient");
    }

    @Override
    @Transactional(readOnly = true)
    public List<SystemParameter> findAll() {
        return jdbcClient.sql(SELECT + " ORDER BY key").query(rowMapper()).list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SystemParameter> find(String key) {
        return jdbcClient
                .sql(SELECT + " WHERE key = :key")
                .param("key", key)
                .query(rowMapper())
                .optional();
    }

    @Override
    @Transactional
    public SystemParameter save(SystemParameter parameter) {
        Guard.notNull(parameter, "parameter");
        jdbcClient
                .sql(UPSERT)
                .param("key", parameter.key())
                .param("value", parameter.value())
                .param("description", parameter.description())
                .param("updatedAt", SqlValues.timestamp(parameter.updatedAt()))
                .param("updatedBy", parameter.updatedBy())
                .update();
        return parameter;
    }

    @Override
    @Transactional
    public void delete(String key) {
        jdbcClient
                .sql("DELETE FROM system_parameter WHERE key = :key")
                .param("key", key)
                .update();
    }

    private static RowMapper<SystemParameter> rowMapper() {
        return (rs, rowNum) -> new SystemParameter(
                rs.getString("key"),
                rs.getString("value"),
                rs.getString("description"),
                SqlValues.instant(rs, "updated_at"),
                rs.getString("updated_by"));
    }
}
