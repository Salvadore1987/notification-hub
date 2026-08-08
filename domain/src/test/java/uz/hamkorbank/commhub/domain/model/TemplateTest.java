package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;

/** Template aggregate: versions per locale and provider-side mapping (FR-4.1, FR-4.5, FR-4.6). */
class TemplateTest {

    @Test
    @DisplayName("FR-4.1: sending resolves the published version of the requested locale")
    void resolvesThePublishedVersionPerLocale() {
        // Arrange
        Template template = newTemplate();
        TemplateVersion russianV1 = version(template, ContentLocale.RU, 1);
        TemplateVersion russianV2 = version(template, ContentLocale.RU, 2);
        TemplateVersion uzbek = version(template, ContentLocale.UZ, 1);
        template.addVersion(russianV1);
        template.addVersion(russianV2);
        template.addVersion(uzbek);

        // Act
        russianV1.submitForReview();
        russianV1.publish("reviewer", NOW);
        russianV2.submitForReview();
        russianV2.publish("reviewer", NOW);

        // Assert
        assertThat(template.publishedVersion(ContentLocale.RU)).contains(russianV2);
        assertThat(template.publishedVersion(ContentLocale.UZ)).isEmpty();
        assertThat(template.publishedVersion(ContentLocale.EN)).isEmpty();
        assertThat(template.latestVersion(ContentLocale.RU)).contains(russianV2);
        assertThat(template.latestVersion(ContentLocale.UZ)).contains(uzbek);
        assertThat(template.publishedVersions()).containsOnlyKeys(ContentLocale.RU);
        assertThat(template.versions()).hasSize(3);
    }

    @Test
    @DisplayName("version numbers are handed out per locale")
    void versionNumbersArePerLocale() {
        // Arrange
        Template template = newTemplate();

        // Act
        template.addVersion(version(template, ContentLocale.RU, template.nextVersionNumber(ContentLocale.RU)));

        // Assert
        assertThat(template.nextVersionNumber(ContentLocale.RU)).isEqualTo(2);
        assertThat(template.nextVersionNumber(ContentLocale.EN)).isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate (locale, version) or a foreign version is refused")
    void versionsAreValidated() {
        // Arrange
        Template template = newTemplate();
        template.addVersion(version(template, ContentLocale.RU, 1));
        TemplateVersion foreign = TemplateVersion.draft(
                TemplateVersionId.newId(),
                TemplateId.newId(),
                1,
                ContentLocale.RU,
                TemplateVersion.Body.ofText("text"),
                "author");

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> template.addVersion(version(template, ContentLocale.RU, 1)))
                .withMessageContaining("already exists");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> template.addVersion(foreign))
                .withMessageContaining("belongs to another template");
    }

    @Test
    @DisplayName("FR-4.5: the provider-side template id is stored with its approval state")
    void providerMappingIsStored() {
        // Arrange
        Template template = newTemplate();
        ProviderCode playmobile = ProviderCode.of("PLAYMOBILE");

        // Act
        template.mapToProviderTemplate(Template.ProviderMapping.pendingApproval(playmobile, "tmpl-1024"));

        // Assert
        assertThat(template.providerMapping(playmobile)).isPresent();
        assertThat(template.providerMapping(playmobile).orElseThrow().approved())
                .isFalse();

        template.mapToProviderTemplate(
                template.providerMapping(playmobile).orElseThrow().approve());
        assertThat(template.providerMapping(playmobile).orElseThrow().approved())
                .isTrue();
        assertThat(template.providerMappings()).hasSize(1);
        assertThat(template.providerMapping(ProviderCode.of("SMSGATE"))).isEmpty();
    }

    @Test
    @DisplayName("FR-4.6: direction and owner describe the migrated template base")
    void metadataIsEditable() {
        // Arrange
        Template template = newTemplate();

        // Act
        template.updateDirection("Ундирув");
        template.updateOwner("collection-department");

        // Assert
        assertThat(template.code()).isEqualTo(TemplateCode.of("OTP_LOGIN"));
        assertThat(template.channel()).isEqualTo(Channel.SMS);
        assertThat(template.direction()).contains("Ундирув");
        assertThat(template.owner()).contains("collection-department");
    }

    @Test
    @DisplayName("an invalid provider mapping is refused")
    void providerMappingIsValidated() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> Template.ProviderMapping.pendingApproval(ProviderCode.of("PLAYMOBILE"), " "));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Template.ProviderMapping(null, "tmpl-1", false));
    }

    @Test
    @DisplayName("FR-4.1: publishing a version archives the one it replaces — a locale has one sendable version")
    void publishingArchivesThePreviousVersion() {
        // Arrange
        Template template = newTemplate();
        TemplateVersion first = version(template, ContentLocale.RU, 1);
        TemplateVersion second = version(template, ContentLocale.RU, 2);
        template.addVersion(first);
        template.addVersion(second);
        first.submitForReview();
        second.submitForReview();
        template.publishVersion(first, "reviewer", NOW);

        // Act
        template.publishVersion(second, "reviewer", NOW);

        // Assert
        assertThat(first.status()).isEqualTo(TemplateStatus.ARCHIVED);
        assertThat(second.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(template.publishedVersion(ContentLocale.RU)).contains(second);
    }

    @Test
    @DisplayName("FR-4.2: a rejected publication leaves the version it would have replaced published")
    void refusedPublicationKeepsThePreviousVersion() {
        // Arrange
        Template template = newTemplate();
        TemplateVersion published = version(template, ContentLocale.RU, 1);
        TemplateVersion candidate = version(template, ContentLocale.RU, 2);
        template.addVersion(published);
        template.addVersion(candidate);
        published.submitForReview();
        template.publishVersion(published, "reviewer", NOW);
        candidate.submitForReview();

        // Act + Assert — "author" is the author of the candidate (maker/checker)
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> template.publishVersion(candidate, "author", NOW));
        assertThat(published.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(template.publishedVersion(ContentLocale.RU)).contains(published);
    }

    @Test
    @DisplayName("a version of another template cannot be published through this one")
    void refusesForeignVersion() {
        // Arrange
        Template template = newTemplate();
        Template other = Template.create(
                TemplateId.newId(), TemplateCode.of("OTP_PAYMENT"), Channel.SMS, "Чакана", "retail-department");
        TemplateVersion foreign = version(other, ContentLocale.RU, 1);
        foreign.submitForReview();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> template.publishVersion(foreign, "reviewer", NOW));
    }

    @Test
    @DisplayName("FR-4.1: an archived card is not sendable and publishes nothing, restoring brings it back")
    void catalogueCardIsArchivedNotDeleted() {
        // Arrange
        Template template = newTemplate();
        TemplateVersion draft = version(template, ContentLocale.RU, 1);
        template.addVersion(draft);
        draft.submitForReview();

        // Act
        template.archive();

        // Assert
        assertThat(template.catalogStatus()).isEqualTo(TemplateCatalogStatus.ARCHIVED);
        assertThat(template.isSendable()).isFalse();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> template.publishVersion(draft, "reviewer", NOW));
        assertThat(template.versions()).hasSize(1);

        // Act + Assert — the card comes back with the history it went away with
        template.restore();
        assertThat(template.isSendable()).isTrue();
        assertThat(template.version(ContentLocale.RU, 1)).contains(draft);
    }

    @Test
    @DisplayName("FR-4.5: a provider mapping can be dropped when the provider goes away")
    void providerMappingCanBeDropped() {
        // Arrange
        Template template = newTemplate();
        template.mapToProviderTemplate(Template.ProviderMapping.pendingApproval(ProviderCode.of("PLAYMOBILE"), "pm-1"));

        // Act
        boolean removed = template.unmapProviderTemplate(ProviderCode.of("PLAYMOBILE"));

        // Assert
        assertThat(removed).isTrue();
        assertThat(template.providerMappings()).isEmpty();
        assertThat(template.unmapProviderTemplate(ProviderCode.of("PLAYMOBILE")))
                .isFalse();
    }

    private static Template newTemplate() {
        return Template.create(
                TemplateId.newId(), TemplateCode.of("OTP_LOGIN"), Channel.SMS, "Чакана", "retail-department");
    }

    private static TemplateVersion version(Template template, ContentLocale locale, int number) {
        return TemplateVersion.draft(
                TemplateVersionId.newId(),
                template.id(),
                number,
                locale,
                TemplateVersion.Body.ofText("Your code is {CODE}"),
                "author");
    }
}
