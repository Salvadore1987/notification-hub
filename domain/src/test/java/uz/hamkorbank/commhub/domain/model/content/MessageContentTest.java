package uz.hamkorbank.commhub.domain.model.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;

/** Sealed content hierarchy of the Message pattern (MP-02, PU-11, EM-01). */
class MessageContentTest {

    @Test
    @DisplayName("SMS content reports its channel and validates text and alpha-name")
    void smsContentInvariants() {
        // Act
        SmsContent content = SmsContent.of("Your code is 1234", "HAMKORBANK");

        // Assert
        assertThat(content.channel()).isEqualTo(Channel.SMS);
        assertThat(content.payloadSizeBytes()).isEqualTo(17);
        assertThat(content.withText("Rendered").text()).isEqualTo("Rendered");
        assertThat(SmsContent.of("text").originator()).isNull();
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> SmsContent.of(" "));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> SmsContent.of("x".repeat(SmsContent.MAX_TEXT_LENGTH + 1)));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> SmsContent.of("text", "TOO_LONG_ORIGINATOR"));
    }

    @Test
    @DisplayName("EM-01: email content requires a subject and at least one body variant")
    void emailContentInvariants() {
        // Act
        EmailContent html = EmailContent.ofHtml("Subject", "<p>Hi</p>", "Hi");

        // Assert
        assertThat(html.channel()).isEqualTo(Channel.EMAIL);
        assertThat(html.isMultipart()).isTrue();
        assertThat(EmailContent.ofText("Subject", "Hi").isMultipart()).isFalse();
        assertThat(html.withRendered("S", "<p>H</p>", "H").subject()).isEqualTo("S");
        assertThat(html.payloadSizeBytes()).isEqualTo("Subject".length() + "<p>Hi</p>".length() + "Hi".length());
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new EmailContent("Subject", null, "  ", List.of(), null))
                .withMessageContaining("requires an htmlBody");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> EmailContent.ofText(" ", "body"));
    }

    @Test
    @DisplayName("EM-01: attachment sizes are summed from the stored metadata")
    void emailAttachmentsAreMeasured() {
        // Arrange
        Attachment statement = new Attachment("statement.pdf", "application/pdf", 2_048L, "s3://bucket/statement");

        // Act
        EmailContent content = new EmailContent(
                "Statement", null, "See attachment", List.of(statement), EmailAddress.of("noreply@hamkorbank.uz"));

        // Assert
        assertThat(content.attachmentsSizeBytes()).isEqualTo(2_048L);
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Attachment("f.pdf", "application/pdf", -1L, "ref"));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Attachment("f.pdf", "application/pdf", 1L, " "));
    }

    @Test
    @DisplayName("PU-11: push payload size is measured against the 4 KB platform limit")
    void pushContentMeasuresItsPayload() {
        // Arrange
        PushContent small = PushContent.of("Title", "Body", Map.of("deepLink", "app://cards"));
        PushContent oversized = PushContent.of("Title", "x".repeat(PushContent.MAX_PAYLOAD_BYTES));

        // Act + Assert
        assertThat(small.channel()).isEqualTo(Channel.PUSH);
        assertThat(small.exceedsPayloadLimit()).isFalse();
        assertThat(oversized.exceedsPayloadLimit()).isTrue();
        assertThat(small.withRendered("T", "B").title()).isEqualTo("T");
        assertThat(PushContent.of("T", "B").data()).isEmpty();
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> PushContent.of("T", " "));
    }

    @Test
    @DisplayName("MP-02: one notification may carry content for several channels")
    void contentsMayCoverSeveralChannels() {
        // Arrange
        SmsContent sms = SmsContent.of("Sms text");
        PushContent push = PushContent.of("Title", "Body");

        // Act
        MessageContents contents = MessageContents.of(sms, push);

        // Assert
        assertThat(contents.channels()).containsExactlyInAnyOrder(Channel.SMS, Channel.PUSH);
        assertThat(contents.size()).isEqualTo(2);
        assertThat(contents.supports(Channel.EMAIL)).isFalse();
        assertThat(contents.requireForChannel(Channel.SMS)).isEqualTo(sms);
        assertThat(contents.forChannel(Channel.EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("content of a channel can be replaced by its rendered form (FR-4.3)")
    void contentsCanBeReplaced() {
        // Arrange
        MessageContents contents = MessageContents.of(SmsContent.of("Hello {NAME}"));

        // Act
        MessageContents rendered = contents.with(SmsContent.of("Hello IVAN"));

        // Assert
        assertThat(((SmsContent) rendered.requireForChannel(Channel.SMS)).text())
                .isEqualTo("Hello IVAN");
        assertThat(((SmsContent) contents.requireForChannel(Channel.SMS)).text())
                .isEqualTo("Hello {NAME}");
    }

    @Test
    @DisplayName("empty, duplicated or mismatched content maps are rejected")
    void contentsRejectInvalidMaps() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new MessageContents(Map.of()))
                .withMessageContaining("at least one channel");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new MessageContents(Map.of(Channel.EMAIL, SmsContent.of("text"))))
                .withMessageContaining("SMS payload");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> MessageContents.of(SmsContent.of("a"), SmsContent.of("b")))
                .withMessageContaining("duplicate content");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> MessageContents.of(SmsContent.of("a")).requireForChannel(Channel.PUSH))
                .withMessageContaining("no message content for channel PUSH");
    }
}
