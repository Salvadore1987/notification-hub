package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;

/** Playmobile delivery reports become canonical status commands (PM-02, ST-03, §18.1). */
class PlaymobileCallbackTranslatorTest {

    private final PlaymobileCallbackTranslator translator = new PlaymobileCallbackTranslator(
            PlaymobileProperties.defaults(), new PlaymobileJson(), FixedClock.standard());

    @Test
    @DisplayName("§18.1: a delivered report becomes DELIVERED, keyed by the message-id the Hub generated")
    void translatesDeliveredReport() {
        // Arrange
        String body = """
                {"message-id":"HB0001","channel":"sms","status":"delivered",
                 "status-date":"2026-08-08T10:05:00Z","description":"ok"}
                """;

        // Act
        List<ProviderStatusCommand> reports = translator.translate(body, Map.of());

        // Assert
        assertThat(reports).hasSize(1);
        ProviderStatusCommand report = reports.getFirst();
        assertThat(report.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(report.providerMessageIdOptional()).map(Object::toString).contains("HB0001");
        assertThat(report.providerCode().value()).isEqualTo("PLAYMOBILE");
        assertThat(report.occurredAt()).isEqualTo(Instant.parse("2026-08-08T10:05:00Z"));
    }

    @Test
    @DisplayName("§18.1: not_delivered and expired map onto the outcomes the state machine allows")
    void translatesFailureOutcomes() {
        // Act
        MessageStatus notDelivered = translator
                .translate("{\"message-id\":\"HB1\",\"status\":\"not_delivered\"}", Map.of())
                .getFirst()
                .status();
        MessageStatus expired = translator
                .translate("{\"message-id\":\"HB2\",\"status\":\"expired\"}", Map.of())
                .getFirst()
                .status();
        MessageStatus transmitted = translator
                .translate("{\"message-id\":\"HB3\",\"status\":\"transmitted\"}", Map.of())
                .getFirst()
                .status();

        // Assert
        assertThat(notDelivered).isEqualTo(MessageStatus.UNDELIVERED);
        assertThat(expired).isEqualTo(MessageStatus.EXPIRED);
        assertThat(transmitted).isEqualTo(MessageStatus.SENT_TO_PROVIDER);
    }

    @Test
    @DisplayName("§18.1: an array of reports is accepted, since providers batch their receipts")
    void translatesBatchedReports() {
        // Arrange
        String body = """
                [{"message-id":"HB1","status":"delivered"},{"message-id":"HB2","status":"failed"}]
                """;

        // Act
        List<ProviderStatusCommand> reports = translator.translate(body, Map.of());

        // Assert
        assertThat(reports).hasSize(2);
        assertThat(reports.get(1).status()).isEqualTo(MessageStatus.UNDELIVERED);
    }

    @Test
    @DisplayName("§18.1: a status word the table does not know is dropped, not refused")
    void dropsUnknownStatusWord() {
        // Act
        List<ProviderStatusCommand> reports =
                translator.translate("{\"message-id\":\"HB1\",\"status\":\"pondering\"}", Map.of());

        // Assert: an empty list means the endpoint answers 200 and the provider stops retrying it.
        assertThat(reports).isEmpty();
    }

    @Test
    @DisplayName("PM-02: a report without a message-id is refused with the field named")
    void refusesReportWithoutMessageId() {
        // Act + Assert
        assertThatThrownBy(() -> translator.translate("{\"status\":\"delivered\"}", Map.of()))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("message-id");
    }

    @Test
    @DisplayName("PM-02: a report without a status is refused")
    void refusesReportWithoutStatus() {
        // Act + Assert
        assertThatThrownBy(() -> translator.translate("{\"message-id\":\"HB1\"}", Map.of()))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("§18.1: the space-separated date form is accepted; an unreadable one falls back to reception time")
    void parsesBothDateForms() {
        // Act
        Instant spaceSeparated = translator
                .translate(
                        "{\"message-id\":\"HB1\",\"status\":\"delivered\",\"status-date\":\"2026-08-08 10:05:00\"}",
                        Map.of())
                .getFirst()
                .occurredAt();
        Instant unreadable = translator
                .translate("{\"message-id\":\"HB2\",\"status\":\"delivered\",\"status-date\":\"yesterday\"}", Map.of())
                .getFirst()
                .occurredAt();

        // Assert
        assertThat(spaceSeparated).isEqualTo(Instant.parse("2026-08-08T10:05:00Z"));
        assertThat(unreadable).isEqualTo(FixedClock.DEFAULT);
    }

    @Test
    @DisplayName("§9.1: a report about the voice channel is ignored — the Hub does not send on it")
    void ignoresNonSmsChannel() {
        // Act
        List<ProviderStatusCommand> reports = translator.translate(
                "{\"message-id\":\"HB1\",\"channel\":\"call\",\"status\":\"delivered\"}", Map.of());

        // Assert
        assertThat(reports).isEmpty();
    }

    @Test
    @DisplayName("PM-02: a body that is not JSON is refused")
    void refusesUnreadableBody() {
        // Act + Assert
        assertThatThrownBy(() -> translator.translate("not json at all", Map.of()))
                .isInstanceOf(InboundContractException.class);
    }
}
