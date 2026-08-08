package uz.hamkorbank.commhub.application.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.port.out.TemplateRepository;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;

/** Rendering an email from a template: subject, both alternatives, and what the submission keeps (EM-01, FR-4.3). */
class TemplateApplierTest {

    private static final TemplateCode CODE = TemplateCode.of("STATEMENT");

    private TemplateRepository templates;
    private TemplateApplier applier;

    @BeforeEach
    void setUp() {
        templates = mock(TemplateRepository.class);
        applier = new TemplateApplier(templates);
    }

    @Test
    @DisplayName("EM-01: an email template renders the subject, the text and the HTML alternative")
    void rendersBothAlternatives() {
        // Arrange
        publish(TemplateVersion.Body.ofEmail(
                "Выписка за {PERIOD}", "Выписка за {PERIOD} готова.", "<p>Выписка за {PERIOD} готова.</p>"));

        // Act
        TemplateOutcome outcome = applier.apply(null, ref(Map.of("PERIOD", "июль")), ContentLocale.RU);

        // Assert
        assertThat(outcome.isRejected()).isFalse();
        EmailContent content = (EmailContent) outcome.contents().requireForChannel(Channel.EMAIL);
        assertThat(content.subject()).isEqualTo("Выписка за июль");
        assertThat(content.textBody()).isEqualTo("Выписка за июль готова.");
        assertThat(content.htmlBody()).isEqualTo("<p>Выписка за июль готова.</p>");
        assertThat(content.isMultipart()).isTrue();
    }

    @Test
    @DisplayName("FR-4.3: the template wins over the submitted body, the submission keeps its attachments and sender")
    void templateWinsButKeepsWhatItHasNoOpinionAbout() {
        // Arrange
        publish(TemplateVersion.Body.ofEmail("Выписка", "Текст шаблона", "<p>Шаблон</p>"));
        EmailContent submitted = new EmailContent(
                "Тема системы-источника",
                "<p>Своя вёрстка</p>",
                "Свой текст",
                List.of(),
                EmailAddress.of("sme@bank.uz"));

        // Act
        TemplateOutcome outcome = applier.apply(MessageContents.of(submitted), ref(Map.of()), ContentLocale.RU);

        // Assert
        EmailContent content = (EmailContent) outcome.contents().requireForChannel(Channel.EMAIL);
        assertThat(content.subject()).isEqualTo("Выписка");
        assertThat(content.textBody()).isEqualTo("Текст шаблона");
        assertThat(content.htmlBody()).isEqualTo("<p>Шаблон</p>");
        assertThat(content.from()).isEqualTo(EmailAddress.of("sme@bank.uz"));
    }

    @Test
    @DisplayName("EM-01: a plain-text template leaves the HTML the source system sent instead of dropping it")
    void keepsSubmittedHtmlWhenTheTemplateHasNone() {
        // Arrange
        publish(TemplateVersion.Body.of("Выписка", "Текст шаблона"));
        EmailContent submitted = EmailContent.ofHtml("Тема", "<p>Своя вёрстка</p>", "Свой текст");

        // Act
        TemplateOutcome outcome = applier.apply(MessageContents.of(submitted), ref(Map.of()), ContentLocale.RU);

        // Assert
        EmailContent content = (EmailContent) outcome.contents().requireForChannel(Channel.EMAIL);
        assertThat(content.textBody()).isEqualTo("Текст шаблона");
        assertThat(content.htmlBody()).isEqualTo("<p>Своя вёрстка</p>");
    }

    private void publish(TemplateVersion.Body body) {
        Template template = Template.create(TemplateId.newId(), CODE, Channel.EMAIL, null, null);
        TemplateVersion version =
                TemplateVersion.draft(TemplateVersionId.newId(), template.id(), 1, ContentLocale.RU, body, "author");
        version.submitForReview();
        template.addVersion(version);
        template.publishVersion(version, "reviewer", NOW);
        when(templates.findByCode(CODE)).thenReturn(Optional.of(template));
    }

    private static TemplateRef ref(Map<String, String> variables) {
        return new TemplateRef(CODE, ContentLocale.RU, variables);
    }
}
