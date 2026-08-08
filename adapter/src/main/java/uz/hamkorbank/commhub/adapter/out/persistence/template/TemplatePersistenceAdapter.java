package uz.hamkorbank.commhub.adapter.out.persistence.template;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.adapter.out.persistence.support.SqlValues;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;

/**
 * {@link TemplateRepository} over {@code template}, {@code template_version} and
 * {@code template_provider_mapping} (§10.1, FR-4.1…FR-4.6).
 *
 * <p>A version is restored by replaying its workflow — draft, review, publish, archive — because that
 * path is exactly what the stored columns describe and it keeps the maker/checker rule live: a row whose
 * reviewer equals its author is rejected on load instead of quietly becoming sendable (FR-4.2).
 *
 * <p>{@code template.status} is the state of the catalogue card and has no counterpart in the aggregate,
 * which tracks status per version; archived cards are therefore written but never read back into a
 * {@link Template}.
 */
@Repository
public class TemplatePersistenceAdapter implements TemplateRepository {

    private static final String SELECT_TEMPLATE = "SELECT id, code, channel, direction, owner, status FROM template";

    private static final String UPSERT_TEMPLATE = """
            INSERT INTO template (id, code, channel, direction, owner)
            VALUES (:id, :code, :channel, :direction, :owner)
            ON CONFLICT (id) DO UPDATE SET
                code = EXCLUDED.code,
                channel = EXCLUDED.channel,
                direction = EXCLUDED.direction,
                owner = EXCLUDED.owner,
                updated_at = now()
            """;

    private static final String SELECT_VERSIONS = """
            SELECT id, template_id, version, locale, subject, body, status, created_by, reviewed_by, published_at
            FROM template_version
            WHERE template_id = :templateId
            ORDER BY locale, version
            """;

    private static final String UPSERT_VERSION = """
            INSERT INTO template_version (id, template_id, version, locale, subject, body, variables, status,
                                          created_by, reviewed_by, published_at)
            VALUES (:id, :templateId, :version, :locale, :subject, :body, CAST(:variables AS jsonb), :status,
                    :createdBy, :reviewedBy, :publishedAt)
            ON CONFLICT (id) DO UPDATE SET
                subject = EXCLUDED.subject,
                body = EXCLUDED.body,
                variables = EXCLUDED.variables,
                status = EXCLUDED.status,
                reviewed_by = EXCLUDED.reviewed_by,
                published_at = EXCLUDED.published_at,
                updated_at = now()
            """;

    private static final String SELECT_MAPPINGS = """
            SELECT provider_code, provider_template_id, approved
            FROM template_provider_mapping
            WHERE template_id = :templateId
            """;

    private static final String UPSERT_MAPPING = """
            INSERT INTO template_provider_mapping (template_id, provider_code, provider_template_id, approved)
            VALUES (:templateId, :providerCode, :providerTemplateId, :approved)
            ON CONFLICT (template_id, provider_code) DO UPDATE SET
                provider_template_id = EXCLUDED.provider_template_id,
                approved = EXCLUDED.approved,
                updated_at = now()
            """;

    private final JdbcClient jdbcClient;
    private final JsonCodec jsonCodec;

    public TemplatePersistenceAdapter(JdbcClient jdbcClient, JsonCodec jsonCodec) {
        this.jdbcClient = jdbcClient;
        this.jsonCodec = jsonCodec;
    }

    @Override
    @Transactional
    public Template save(Template template) {
        jdbcClient
                .sql(UPSERT_TEMPLATE)
                .param("id", template.id().value())
                .param("code", template.code().value())
                .param("channel", template.channel().name())
                .param("direction", template.direction().orElse(null))
                .param("owner", template.owner().orElse(null))
                .update();
        template.versions().forEach(version -> saveVersion(template.id(), version));
        template.providerMappings().values().forEach(mapping -> saveMapping(template.id(), mapping));
        return template;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Template> findById(TemplateId templateId) {
        return load(SELECT_TEMPLATE + " WHERE id = :value", templateId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Template> findByCode(TemplateCode code) {
        return load(SELECT_TEMPLATE + " WHERE code = :value", code.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Template> findByCode(TemplateCode code, Channel channel) {
        return findByCode(code).filter(template -> template.channel() == channel);
    }

    private Optional<Template> load(String sql, Object value) {
        return jdbcClient
                .sql(sql)
                .param("value", value)
                .query(templateRowMapper())
                .optional()
                .map(this::withVersionsAndMappings);
    }

    private Template withVersionsAndMappings(Template template) {
        jdbcClient
                .sql(SELECT_VERSIONS)
                .param("templateId", template.id().value())
                .query(versionRowMapper())
                .list()
                .forEach(template::addVersion);
        jdbcClient
                .sql(SELECT_MAPPINGS)
                .param("templateId", template.id().value())
                .query((rs, rowNum) -> new Template.ProviderMapping(
                        ProviderCode.of(rs.getString("provider_code")),
                        rs.getString("provider_template_id"),
                        rs.getBoolean("approved")))
                .list()
                .forEach(template::mapToProviderTemplate);
        return template;
    }

    private RowMapper<Template> templateRowMapper() {
        return (rs, rowNum) -> Template.create(
                TemplateId.of(SqlValues.uuid(rs, "id")),
                TemplateCode.of(rs.getString("code")),
                SqlValues.enumValue(rs, "channel", Channel.class),
                rs.getString("direction"),
                rs.getString("owner"));
    }

    private RowMapper<TemplateVersion> versionRowMapper() {
        return (rs, rowNum) -> {
            TemplateVersion version = TemplateVersion.draft(
                    TemplateVersionId.of(SqlValues.uuid(rs, "id")),
                    TemplateId.of(SqlValues.uuid(rs, "template_id")),
                    rs.getInt("version"),
                    SqlValues.enumValue(rs, "locale", ContentLocale.class),
                    new TemplateVersion.Body(rs.getString("subject"), rs.getString("body")),
                    rs.getString("created_by"));
            replayWorkflow(version, rs);
            return version;
        };
    }

    /** Walks the version through the transitions that produced its stored status (FR-4.1, FR-4.2). */
    private void replayWorkflow(TemplateVersion version, ResultSet rs) throws SQLException {
        TemplateStatus status = SqlValues.enumValue(rs, "status", TemplateStatus.class);
        Instant publishedAt = SqlValues.instant(rs, "published_at");
        boolean wasPublished = status == TemplateStatus.PUBLISHED || publishedAt != null;
        if (status == TemplateStatus.ON_REVIEW || wasPublished) {
            version.submitForReview();
        }
        if (wasPublished) {
            version.publish(rs.getString("reviewed_by"), publishedAt);
        }
        if (status == TemplateStatus.ARCHIVED) {
            version.archive();
        }
    }

    private void saveVersion(TemplateId templateId, TemplateVersion version) {
        jdbcClient
                .sql(UPSERT_VERSION)
                .param("id", version.id().value())
                .param("templateId", templateId.value())
                .param("version", version.version())
                .param("locale", version.locale().name())
                .param("subject", version.body().subject())
                .param("body", version.body().text())
                .param("variables", jsonCodec.write(List.copyOf(version.declaredVariables())))
                .param("status", version.status().name())
                .param("createdBy", version.createdBy())
                .param("reviewedBy", version.reviewedBy().orElse(null))
                .param("publishedAt", SqlValues.timestamp(version.publishedAt().orElse(null)))
                .update();
    }

    private void saveMapping(TemplateId templateId, Template.ProviderMapping mapping) {
        jdbcClient
                .sql(UPSERT_MAPPING)
                .param("templateId", templateId.value())
                .param("providerCode", mapping.providerCode().value())
                .param("providerTemplateId", mapping.providerTemplateId())
                .param("approved", mapping.approved())
                .update();
    }

    /** Kept for the admin panel: the catalogue card may be archived without touching its versions. */
    @Transactional
    public void archive(TemplateId templateId) {
        jdbcClient
                .sql("UPDATE template SET status = 'ARCHIVED', updated_at = now() WHERE id = :id")
                .param("id", templateId.value())
                .update();
    }
}
