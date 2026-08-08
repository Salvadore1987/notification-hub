package uz.hamkorbank.commhub.adapter.out.persistence.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;

/** Templates keep their versions, their locales and their publication state across a restart (FR-4.1…FR-4.5). */
class TemplatePersistenceIT extends AbstractPersistenceIT {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-08-01T10:00:00Z");

    private final TemplatePersistenceAdapter templates;

    TemplatePersistenceIT(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, TemplatePersistenceAdapter templates) {
        super(jdbcClient, transactionTemplate);
        this.templates = templates;
    }

    @BeforeEach
    void clearTemplates() {
        truncate("template_provider_mapping", "template_version", "template");
    }

    @Test
    @DisplayName("a template round-trips with a published and a draft version (FR-4.1)")
    void templateRoundTrips() {
        // Arrange
        Template template =
                Template.create(TemplateId.newId(), TemplateCode.of("OTP_LOGIN"), Channel.SMS, "Чакана", "retail-team");
        TemplateVersion published = TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                1,
                ContentLocale.RU,
                new TemplateVersion.Body(null, "Код подтверждения: {CODE}"),
                "author-1");
        published.submitForReview();
        published.publish("reviewer-1", PUBLISHED_AT);
        template.addVersion(published);
        template.addVersion(TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                2,
                ContentLocale.RU,
                new TemplateVersion.Body(null, "Ваш код: {CODE}"),
                "author-1"));
        template.mapToProviderTemplate(
                new Template.ProviderMapping(ProviderCode.of("PLAYMOBILE"), "pm-otp-login", true));

        // Act
        templates.save(template);
        Template restored = templates.findByCode(TemplateCode.of("OTP_LOGIN")).orElseThrow();

        // Assert
        assertThat(restored.id()).isEqualTo(template.id());
        assertThat(restored.direction()).contains("Чакана");
        assertThat(restored.versions()).hasSize(2);
        assertThat(restored.publishedVersion(ContentLocale.RU)).isPresent();
        TemplateVersion restoredPublished =
                restored.publishedVersion(ContentLocale.RU).orElseThrow();
        assertThat(restoredPublished.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(restoredPublished.reviewedBy()).contains("reviewer-1");
        assertThat(restoredPublished.publishedAt()).contains(PUBLISHED_AT);
        assertThat(restoredPublished.declaredVariables()).containsExactly("CODE");
        assertThat(restored.latestVersion(ContentLocale.RU).orElseThrow().version())
                .isEqualTo(2);
        assertThat(restored.providerMapping(ProviderCode.of("PLAYMOBILE"))
                        .orElseThrow()
                        .providerTemplateId())
                .isEqualTo("pm-otp-login");
    }

    @Test
    @DisplayName("an archived version comes back archived, not sendable (FR-4.1)")
    void archivedVersionStaysArchived() {
        // Arrange
        Template template = Template.create(TemplateId.newId(), TemplateCode.of("PROMO"), Channel.SMS, null, null);
        TemplateVersion version = TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                1,
                ContentLocale.UZ,
                new TemplateVersion.Body(null, "Chegirma {PERCENT}%"),
                "author-2");
        version.archive();
        template.addVersion(version);
        templates.save(template);

        // Act
        Template restored = templates.findById(template.id()).orElseThrow();

        // Assert
        assertThat(restored.versions().getFirst().status()).isEqualTo(TemplateStatus.ARCHIVED);
        assertThat(restored.versions().getFirst().isSendable()).isFalse();
        assertThat(restored.publishedVersion(ContentLocale.UZ)).isEmpty();
    }

    @Test
    @DisplayName("a lookup by code and channel rejects a template of another channel")
    void lookupIsChannelAware() {
        // Arrange
        Template template =
                Template.create(TemplateId.newId(), TemplateCode.of("STATEMENT"), Channel.EMAIL, null, null);
        templates.save(template);

        // Act + Assert
        assertThat(templates.findByCode(TemplateCode.of("STATEMENT"), Channel.EMAIL))
                .isPresent();
        assertThat(templates.findByCode(TemplateCode.of("STATEMENT"), Channel.SMS))
                .isEmpty();
    }
}
