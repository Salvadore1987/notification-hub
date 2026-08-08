package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;

/** Template version: review workflow and merge-field rendering (FR-4.1, FR-4.2, FR-4.3). */
class TemplateVersionTest {

    private static final TemplateId TEMPLATE_ID = TemplateId.newId();

    @Test
    @DisplayName("FR-4.1: a new version starts as DRAFT and is not sendable")
    void draftIsNotSendable() {
        // Act
        TemplateVersion version = draft("Hello {NAME}");

        // Assert
        assertThat(version.status()).isEqualTo(TemplateStatus.DRAFT);
        assertThat(version.isSendable()).isFalse();
        assertThat(version.version()).isEqualTo(1);
        assertThat(version.locale()).isEqualTo(ContentLocale.RU);
        assertThat(version.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(version.createdBy()).isEqualTo("author");
        assertThat(version.reviewedBy()).isEmpty();
        assertThat(version.publishedAt()).isEmpty();
    }

    @Test
    @DisplayName("FR-4.1: DRAFT → ON_REVIEW → PUBLISHED → ARCHIVED")
    void reviewWorkflow() {
        // Arrange
        TemplateVersion version = draft("Hello {NAME}");

        // Act
        version.submitForReview();
        version.publish("reviewer", NOW);

        // Assert
        assertThat(version.status()).isEqualTo(TemplateStatus.PUBLISHED);
        assertThat(version.isSendable()).isTrue();
        assertThat(version.reviewedBy()).contains("reviewer");
        assertThat(version.publishedAt()).contains(NOW);

        version.archive();
        assertThat(version.status()).isEqualTo(TemplateStatus.ARCHIVED);
        assertThat(version.isSendable()).isFalse();
    }

    @Test
    @DisplayName("FR-4.2: maker/checker — the author may not publish their own version")
    void authorCannotPublishOwnVersion() {
        // Arrange
        TemplateVersion version = draft("Hello {NAME}");
        version.submitForReview();

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> version.publish("AUTHOR", NOW))
                .withMessageContaining("maker/checker");
        assertThat(version.status()).isEqualTo(TemplateStatus.ON_REVIEW);
    }

    @Test
    @DisplayName("a draft cannot be published without a review")
    void publicationRequiresReview() {
        // Arrange
        TemplateVersion version = draft("Hello {NAME}");

        // Act + Assert
        assertThatExceptionOfType(InvalidStatusTransitionException.class)
                .isThrownBy(() -> version.publish("reviewer", NOW))
                .withMessageContaining("DRAFT -> PUBLISHED");
    }

    @Test
    @DisplayName("a version on review can be returned to its author")
    void reviewMayReturnToDraft() {
        // Arrange
        TemplateVersion version = draft("Hello {NAME}");
        version.submitForReview();

        // Act
        version.returnToDraft();

        // Assert
        assertThat(version.status()).isEqualTo(TemplateStatus.DRAFT);
    }

    @Test
    @DisplayName("FR-4.3: merge fields are substituted from the supplied variables")
    void rendersMergeFields() {
        // Arrange
        TemplateVersion version = published("Hello {NAME}, your balance is {AMOUNT} UZS");

        // Act
        TemplateVersion.Rendered rendered = version.render(Map.of("NAME", "IVAN", "AMOUNT", "1 000"), true);

        // Assert
        assertThat(rendered.text()).isEqualTo("Hello IVAN, your balance is 1 000 UZS");
        assertThat(rendered.subjectOptional()).isEmpty();
        assertThat(version.declaredVariables()).containsExactlyInAnyOrder("NAME", "AMOUNT");
    }

    @Test
    @DisplayName("FR-4.3: strict mode fails on a missing variable, lenient mode blanks it out")
    void strictModeFailsOnMissingVariable() {
        // Arrange
        TemplateVersion version = published("Hello {NAME}!");

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> version.render(Map.of(), true))
                .withMessageContaining("missing value for merge field {NAME}");
        assertThat(version.render(Map.of(), false).text()).isEqualTo("Hello !");
        assertThat(version.render(null, false).text()).isEqualTo("Hello !");
    }

    @Test
    @DisplayName("FR-4.1: only a published version may be rendered")
    void onlyPublishedVersionsRender() {
        // Arrange
        TemplateVersion version = draft("Hello {NAME}");

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> version.render(Map.of("NAME", "IVAN"), true))
                .withMessageContaining("only a PUBLISHED template version");
    }

    @Test
    @DisplayName("an email subject is rendered together with the body")
    void rendersSubjectAndBody() {
        // Arrange
        TemplateVersion version = TemplateVersion.draft(
                TemplateVersionId.newId(),
                TEMPLATE_ID,
                1,
                ContentLocale.EN,
                TemplateVersion.Body.of("Statement for {MONTH}", "Dear {NAME}, see the attachment."),
                "author");
        version.submitForReview();
        version.publish("reviewer", NOW);

        // Act
        TemplateVersion.Rendered rendered = version.render(Map.of("MONTH", "July", "NAME", "Ivan"), true);

        // Assert
        assertThat(rendered.subject()).isEqualTo("Statement for July");
        assertThat(rendered.text()).isEqualTo("Dear Ivan, see the attachment.");
        assertThat(version.declaredVariables()).containsExactlyInAnyOrder("MONTH", "NAME");
        assertThat(version.body().subject()).isEqualTo("Statement for {MONTH}");
    }

    @Test
    @DisplayName("a substituted value with a dollar sign does not break the rendering")
    void substitutionEscapesReplacementSyntax() {
        // Arrange
        TemplateVersion version = published("Amount: {AMOUNT}");

        // Act
        TemplateVersion.Rendered rendered = version.render(Map.of("AMOUNT", "$100"), true);

        // Assert
        assertThat(rendered.text()).isEqualTo("Amount: $100");
    }

    @Test
    @DisplayName("invalid bodies, authors and versions are rejected")
    void invariantsAreEnforced() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> TemplateVersion.Body.ofText(" "));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> TemplateVersion.Body.ofText("x".repeat(TemplateVersion.Body.MAX_TEXT_LENGTH + 1)));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> TemplateVersion.draft(
                        TemplateVersionId.newId(),
                        TEMPLATE_ID,
                        0,
                        ContentLocale.RU,
                        TemplateVersion.Body.ofText("text"),
                        "author"));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> TemplateVersion.draft(
                        TemplateVersionId.newId(),
                        TEMPLATE_ID,
                        1,
                        ContentLocale.RU,
                        TemplateVersion.Body.ofText("text"),
                        " "));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new TemplateVersion.Rendered(null, " "));
    }

    private static TemplateVersion draft(String text) {
        return TemplateVersion.draft(
                TemplateVersionId.newId(),
                TEMPLATE_ID,
                1,
                ContentLocale.RU,
                TemplateVersion.Body.ofText(text),
                "author");
    }

    private static TemplateVersion published(String text) {
        TemplateVersion version = draft(text);
        version.submitForReview();
        version.publish("reviewer", NOW);
        return version;
    }
}
