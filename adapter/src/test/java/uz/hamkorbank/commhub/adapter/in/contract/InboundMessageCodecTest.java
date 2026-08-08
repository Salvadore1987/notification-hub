package uz.hamkorbank.commhub.adapter.in.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelSelectionMode;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/** The inbound document of IK-03 → {@code SubmitMessageCommand} (§8.1, §8.2). */
class InboundMessageCodecTest {

    private final InboundMessageCodec codec =
            new InboundMessageCodec(new InboundJson(), new InboundPayloadMapperImpl());

    /** The example document printed in IK-03, field for field. */
    private static final String IK03_EXAMPLE = """
            {
              "schemaVersion": "1.0",
              "streamId": "ibank-retail",
              "externalMessageId": "ibank-2026-000123",
              "batchId": null,
              "trafficClass": "CRITICAL_OTP",
              "priority": "REALTIME",
              "recipient": { "clientId": "C123", "msisdn": "998901234567",
                             "email": "client@example.uz", "pushTokens": [{"platform":"IOS","token":"abc"}] },
              "channels": { "requested": ["SMS"], "fallbackPolicy": null },
              "content": { "sms": { "text": "Kod: 123456", "originator": "Hamkorbank" } },
              "template": { "id": null, "variables": {} },
              "timing": { "sendAfter": null, "sendBefore": null, "ttlSeconds": 300,
                          "allowedWindow": null, "sendEvenly": false, "localTime": false },
              "dedupKey": "otp-C123-login-20260806",
              "correlationId": "8f14e45f-1234-4a2b-8c3d-000000000001"
            }
            """;

    @Test
    @DisplayName("IK-03: reads the specification's own example into a command")
    void readsTheSpecificationExample() {
        // Arrange + Act
        SubmitMessageCommand command = codec.read(IK03_EXAMPLE);

        // Assert
        assertThat(command.streamId().value()).isEqualTo("ibank-retail");
        assertThat(command.externalMessageId().value()).isEqualTo("ibank-2026-000123");
        assertThat(command.batchId()).isNull();
        assertThat(command.recipient().msisdn().value()).isEqualTo("998901234567");
        assertThat(command.recipient().email().value()).isEqualTo("client@example.uz");
        assertThat(command.recipient().pushTokens()).singleElement().satisfies(token -> assertThat(token.platform())
                .isEqualTo(PushPlatform.IOS));
        assertThat(command.contents().requireForChannel(Channel.SMS))
                .isEqualTo(SmsContent.of("Kod: 123456", "Hamkorbank"));
        assertThat(command.channelPlan().mode()).isEqualTo(ChannelSelectionMode.EXPLICIT);
        assertThat(command.template()).isNull();
        assertThat(command.delivery().trafficClass()).isEqualTo(TrafficClass.CRITICAL_OTP);
        assertThat(command.delivery().priority()).isEqualTo(Priority.REALTIME);
        assertThat(command.delivery().timing().ttl()).isEqualTo(Duration.ofSeconds(300));
        assertThat(command.delivery().dedupKey().value()).isEqualTo("otp-C123-login-20260806");
        assertThat(command.delivery().correlationId().value()).isEqualTo("8f14e45f-1234-4a2b-8c3d-000000000001");
    }

    @Test
    @DisplayName("TC-01: the topic decides the traffic class, not the document")
    void topicOverridesTheClaimedTrafficClass() {
        // Arrange
        String claimsCritical = message("""
                "trafficClass": "CRITICAL_OTP",""");

        // Act
        SubmitMessageCommand command = codec.read(claimsCritical, TrafficClass.NOTIFICATION);

        // Assert
        assertThat(command.delivery().trafficClass()).isEqualTo(TrafficClass.NOTIFICATION);
    }

    @Test
    @DisplayName("A message may reference a template instead of carrying content (FR-1.2)")
    void acceptsATemplateInsteadOfContent() {
        // Arrange
        String withTemplate = """
                {
                  "streamId": "ibank-retail",
                  "externalMessageId": "tpl-1",
                  "recipient": { "msisdn": "998901234567" },
                  "template": { "id": "otp.login", "locale": "uz", "variables": { "code": "1234" } }
                }
                """;

        // Act
        SubmitMessageCommand command = codec.read(withTemplate);

        // Assert
        assertThat(command.contents()).isNull();
        assertThat(command.template().code().value()).isEqualTo("OTP.LOGIN");
        assertThat(command.template().locale()).isEqualTo(ContentLocale.UZ);
        assertThat(command.template().variables()).containsEntry("code", "1234");
    }

    @Test
    @DisplayName("FR-1.2: neither content nor template is refused, naming the field")
    void refusesADocumentWithNeitherContentNorTemplate() {
        // Arrange
        String empty = """
                { "streamId": "ibank-retail", "externalMessageId": "x-1",
                  "recipient": { "msisdn": "998901234567" } }
                """;

        // Act + Assert
        assertThatThrownBy(() -> codec.read(empty))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("content");
    }

    @Test
    @DisplayName("§9.1: an MSISDN that is not 9989xxxxxxxx is refused with a field pointer")
    void refusesAMalformedMsisdn() {
        // Arrange
        String badMsisdn = """
                {
                  "streamId": "ibank-retail",
                  "externalMessageId": "x-2",
                  "recipient": { "msisdn": "+7 926 000-00-00" },
                  "content": { "sms": { "text": "hi" } }
                }
                """;

        // Act + Assert
        assertThatThrownBy(() -> codec.read(badMsisdn))
                .isInstanceOf(InboundContractException.class)
                .satisfies(thrown ->
                        assertThat(((InboundContractException) thrown).field()).isEqualTo("recipient"));
    }

    @Test
    @DisplayName("A required field that is absent names itself")
    void refusesAMissingRequiredField() {
        // Arrange
        String noExternalId = """
                { "streamId": "ibank-retail", "content": { "sms": { "text": "hi" } },
                  "recipient": { "msisdn": "998901234567" } }
                """;

        // Act + Assert
        assertThatThrownBy(() -> codec.read(noExternalId))
                .isInstanceOf(InboundContractException.class)
                .satisfies(thrown ->
                        assertThat(((InboundContractException) thrown).field()).isEqualTo("externalMessageId"));
    }

    @Test
    @DisplayName("An unknown enum constant is a contract violation, not a null")
    void refusesAnUnknownEnumConstant() {
        // Arrange
        String unknownClass = message("""
                "trafficClass": "URGENT",""");

        // Act + Assert
        assertThatThrownBy(() -> codec.read(unknownClass))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("trafficClass");
    }

    @Test
    @DisplayName("§8.1: a document of an unsupported major schema version is refused")
    void refusesAnUnsupportedSchemaVersion() {
        // Arrange
        String futureSchema = message("""
                "schemaVersion": "2.0",""");

        // Act + Assert
        assertThatThrownBy(() -> codec.read(futureSchema))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    @DisplayName("§8.1: unknown fields are ignored so a newer producer keeps working")
    void ignoresUnknownFields() {
        // Arrange
        String withFutureField = message("""
                "somethingAddedLater": { "a": 1 },""");

        // Act
        SubmitMessageCommand command = codec.read(withFutureField);

        // Assert
        assertThat(command.externalMessageId().value()).isEqualTo("x-3");
    }

    @Test
    @DisplayName("A body that is not JSON at all is a contract violation")
    void refusesAnUnreadableBody() {
        // Act + Assert
        assertThatThrownBy(() -> codec.read("<xml/>")).isInstanceOf(InboundContractException.class);
        assertThatThrownBy(() -> codec.read("   ")).isInstanceOf(InboundContractException.class);
    }

    @Test
    @DisplayName("FR-8.5: a send window is read into the timing of the message")
    void readsTheSendWindow() {
        // Arrange
        String scheduled = """
                {
                  "streamId": "ibank-retail",
                  "externalMessageId": "x-4",
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "hi" } },
                  "timing": { "sendAfter": "2026-08-08T06:00:00Z", "sendBefore": "2026-08-08T18:00:00Z",
                              "allowedWindow": { "start": "09:00", "end": "21:00" },
                              "sendEvenly": true, "localTime": true }
                }
                """;

        // Act
        SubmitMessageCommand command = codec.read(scheduled);

        // Assert
        assertThat(command.delivery().timing().sendAfter()).isEqualTo(Instant.parse("2026-08-08T06:00:00Z"));
        assertThat(command.delivery().timing().sendBefore()).isEqualTo(Instant.parse("2026-08-08T18:00:00Z"));
        assertThat(command.delivery().timing().allowedStartTime()).hasToString("09:00");
        assertThat(command.delivery().timing().sendEvenly()).isTrue();
        assertThat(command.delivery().timing().localTime()).isTrue();
    }

    @Test
    @DisplayName("FR-8.1: a fallback chain is read as an ordered channel plan")
    void readsAFallbackChain() {
        // Arrange
        String chain = """
                {
                  "streamId": "ibank-retail",
                  "externalMessageId": "x-5",
                  "recipient": { "msisdn": "998901234567", "email": "c@example.uz" },
                  "channels": { "requested": ["PUSH", "SMS"], "fallbackPolicy": "CHAIN" },
                  "content": { "sms": { "text": "hi" }, "push": { "title": "t", "body": "b" } }
                }
                """;

        // Act
        SubmitMessageCommand command = codec.read(chain);

        // Assert
        assertThat(command.channelPlan().mode()).isEqualTo(ChannelSelectionMode.FALLBACK_CHAIN);
        assertThat(command.channelPlan().channels()).containsExactly(Channel.PUSH, Channel.SMS);
    }

    private static String message(String extraLine) {
        return """
                {
                  %s
                  "streamId": "ibank-retail",
                  "externalMessageId": "x-3",
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "hi" } }
                }
                """.formatted(extraLine);
    }
}
