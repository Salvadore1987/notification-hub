package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileProperties.Sending;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileSendCodec.PlaymobileError;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderTemplateBinding;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** The wire shape of the Playmobile {@code /send} request and of its error answer (§9.1, §18.1). */
class PlaymobileSendCodecTest {

    private final PlaymobileJson json = new PlaymobileJson();

    private final PlaymobileSendCodec codec = new PlaymobileSendCodec(json);

    @Test
    @DisplayName("§9.1: one message becomes a messages[] element with recipient, message-id and sms.content.text")
    void writesSingleMessage() {
        // Arrange
        SmsSubmission submission = submission(SmsContent.of("Kod: 4821"), Timing.immediate(), null);

        // Act
        JsonNode root = json.readOrNull(codec.encode(
                List.of(new PlaymobileSend(submission, ProviderMessageId.of("HB0001"))),
                new Sending("3700", "HB", null, null)));

        // Assert
        JsonNode message = root.get("messages").get(0);
        assertThat(message.get("recipient").asString()).isEqualTo("998901234567");
        assertThat(message.get("message-id").asString()).isEqualTo("HB0001");
        assertThat(message.get("sms").get("originator").asString()).isEqualTo("3700");
        assertThat(message.get("sms").get("content").get("text").asString()).isEqualTo("Kod: 4821");
    }

    @Test
    @DisplayName("PM-03: the priority of a CRITICAL_OTP message is realtime")
    void writesPriorityFromTrafficClass() {
        // Arrange
        SmsSubmission submission = submission(SmsContent.of("Kod: 4821"), Timing.immediate(), null);

        // Act
        JsonNode root = json.readOrNull(codec.encode(
                List.of(new PlaymobileSend(submission, ProviderMessageId.of("HB0001"))), Sending.defaults()));

        // Assert
        assertThat(root.get("priority").asString()).isEqualTo("realtime");
    }

    @Test
    @DisplayName("§9.1: the message originator wins over the configured default")
    void messageOriginatorOverridesTheDefault() {
        // Arrange
        SmsSubmission submission = submission(SmsContent.of("Text", "HAMKOR"), Timing.immediate(), null);

        // Act
        JsonNode root = json.readOrNull(codec.encode(
                List.of(new PlaymobileSend(submission, ProviderMessageId.of("HB0001"))),
                new Sending("3700", "HB", null, null)));

        // Assert
        assertThat(root.get("messages").get(0).get("sms").get("originator").asString())
                .isEqualTo("HAMKOR");
    }

    @Test
    @DisplayName("FR-3.4: a TTL becomes sms.ttl in seconds; without one the field is absent")
    void writesTtlOnlyWhenThereIsOne() {
        // Arrange
        SmsSubmission withTtl = submission(SmsContent.of("Text"), Timing.withTtl(Duration.ofMinutes(5)), null);
        SmsSubmission withoutTtl = submission(SmsContent.of("Text"), Timing.immediate(), null);

        // Act
        JsonNode ttl = json.readOrNull(
                codec.encode(List.of(new PlaymobileSend(withTtl, ProviderMessageId.of("HB1"))), Sending.defaults()));
        JsonNode none = json.readOrNull(
                codec.encode(List.of(new PlaymobileSend(withoutTtl, ProviderMessageId.of("HB2"))), Sending.defaults()));

        // Assert
        assertThat(ttl.get("messages").get(0).get("sms").get("ttl").asInt()).isEqualTo(300);
        assertThat(none.get("messages").get(0).get("sms").get("ttl")).isNull();
    }

    @Test
    @DisplayName("FR-8.5: a send window becomes the timing block; an empty timing is omitted entirely")
    void writesTimingWindow() {
        // Arrange
        Timing window = new Timing(
                Instant.parse("2026-08-08T06:00:00Z"),
                Instant.parse("2026-08-08T18:00:00Z"),
                null,
                true,
                true,
                LocalTime.of(9, 0),
                LocalTime.of(21, 0));

        // Act
        JsonNode root = json.readOrNull(codec.encode(
                List.of(new PlaymobileSend(submission(SmsContent.of("Text"), window, null), ProviderMessageId.of("H"))),
                Sending.defaults()));
        JsonNode bare = json.readOrNull(codec.encode(
                List.of(new PlaymobileSend(
                        submission(SmsContent.of("Text"), Timing.immediate(), null), ProviderMessageId.of("H"))),
                Sending.defaults()));

        // Assert
        JsonNode timing = root.get("timing");
        assertThat(timing.get("localtime").asBoolean()).isTrue();
        assertThat(timing.get("send-evenly").asBoolean()).isTrue();
        assertThat(timing.get("start-datetime").asString()).isEqualTo("2026-08-08T06:00:00Z");
        assertThat(timing.get("end-datetime").asString()).isEqualTo("2026-08-08T18:00:00Z");
        assertThat(timing.get("allowed-starttime").asString()).isEqualTo("09:00");
        assertThat(timing.get("allowed-endtime").asString()).isEqualTo("21:00");
        assertThat(bare.get("timing")).isNull();
    }

    @Test
    @DisplayName("FR-4.5: an approved provider template replaces the text with template-id and variables")
    void writesApprovedTemplateBinding() {
        // Arrange
        ProviderTemplateBinding binding = new ProviderTemplateBinding("otp-ru", Map.of("CODE", "4821"), true);
        SmsSubmission submission = submission(SmsContent.of("Kod: 4821"), Timing.immediate(), binding);

        // Act
        JsonNode content = json.readOrNull(codec.encode(
                        List.of(new PlaymobileSend(submission, ProviderMessageId.of("HB1"))), Sending.defaults()))
                .get("messages")
                .get(0)
                .get("sms")
                .get("content");

        // Assert
        assertThat(content.get("template-id").asString()).isEqualTo("otp-ru");
        assertThat(content.get("variables").get("CODE").asString()).isEqualTo("4821");
        assertThat(content.get("text")).isNull();
    }

    @Test
    @DisplayName("§18.1 code 206: an unapproved template binding is ignored and the rendered text is sent")
    void ignoresUnapprovedTemplateBinding() {
        // Arrange
        ProviderTemplateBinding binding = new ProviderTemplateBinding("otp-ru", Map.of("CODE", "4821"), false);
        SmsSubmission submission = submission(SmsContent.of("Kod: 4821"), Timing.immediate(), binding);

        // Act
        JsonNode content = json.readOrNull(codec.encode(
                        List.of(new PlaymobileSend(submission, ProviderMessageId.of("HB1"))), Sending.defaults()))
                .get("messages")
                .get(0)
                .get("sms")
                .get("content");

        // Assert
        assertThat(content.get("text").asString()).isEqualTo("Kod: 4821");
        assertThat(content.get("template-id")).isNull();
    }

    @Test
    @DisplayName("§9.1: a chunk is the same document with several messages[] elements")
    void writesBulkSendAsOneDocument() {
        // Arrange
        List<PlaymobileSend> sends = List.of(
                new PlaymobileSend(
                        submission(SmsContent.of("A"), Timing.immediate(), null), ProviderMessageId.of("H1")),
                new PlaymobileSend(
                        submission(SmsContent.of("B"), Timing.immediate(), null), ProviderMessageId.of("H2")));

        // Act
        JsonNode root = json.readOrNull(codec.encode(sends, Sending.defaults()));

        // Assert
        assertThat(root.get("messages").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("§18.1: the error answer is read under either spelling of error_code")
    void readsErrorInBothSpellings() {
        // Act
        Optional<PlaymobileError> dashed =
                codec.readError("{\"error-code\":\"102\",\"error-description\":\"Account lock\"}");
        Optional<PlaymobileError> underscored = codec.readError("{\"error_code\":102}");

        // Assert
        assertThat(dashed).isPresent();
        assertThat(dashed.get().code()).isEqualTo("102");
        assertThat(dashed.get().describe()).isEqualTo("Account lock");
        assertThat(underscored).isPresent();
        // A code without a description falls back to the wording of §18.1.
        assertThat(underscored.get().describe()).isEqualTo("Account lock");
    }

    @Test
    @DisplayName("§18.1: a body without an error code is not an error answer")
    void readsNoErrorFromAnUnrelatedBody() {
        // Act + Assert
        assertThat(codec.readError("{\"status\":\"ok\"}")).isEmpty();
        assertThat(codec.readError("not json")).isEmpty();
        assertThat(codec.readError(null)).isEmpty();
    }

    private static SmsSubmission submission(SmsContent content, Timing timing, ProviderTemplateBinding binding) {
        return new SmsSubmission(
                new ProviderRef(
                        ProviderId.newId(),
                        ProviderCode.of("PLAYMOBILE"),
                        Channel.SMS,
                        AdapterType.of("playmobile-http")),
                MessageId.newId(),
                null,
                Msisdn.of("998901234567"),
                content,
                timing,
                binding,
                new SubmissionContext(TrafficClass.CRITICAL_OTP, Priority.REALTIME, CorrelationId.of("corr-1"), false));
    }
}
