package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;

/** SMS Gate FEEDBACK reports become canonical status commands (SG-02, §18.2). */
class SmsGateCallbackTranslatorTest {

    private final SmsGateCallbackTranslator translator =
            new SmsGateCallbackTranslator(SmsGateProperties.defaults(), new SmsGateJson(), FixedClock.standard());

    @Test
    @DisplayName("§18.2: code 4 Delivered becomes DELIVERED, keyed by the provider-assigned id")
    void translatesDeliveredReport() {
        // Arrange
        String body = "{\"login\":\"hamkor\",\"key\":\"k3y\",\"id\":\"98765\",\"code\":\"4\","
                + "\"description\":\"Delivered\"}";

        // Act
        List<ProviderStatusCommand> reports = translator.translate(body, Map.of());

        // Assert
        assertThat(reports).hasSize(1);
        ProviderStatusCommand report = reports.getFirst();
        assertThat(report.status()).isEqualTo(MessageStatus.DELIVERED);
        assertThat(report.providerMessageIdOptional()).map(Object::toString).contains("98765");
        assertThat(report.providerCode().value()).isEqualTo("SMSGATE");
    }

    @Test
    @DisplayName("§9.2: the same report is read from form fields, which is how some contours post it")
    void translatesFormEncodedReport() {
        // Act
        List<ProviderStatusCommand> reports =
                translator.translate(null, Map.of("id", "98765", "code", "2", "description", "Fail"));

        // Assert
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.status()).isEqualTo(MessageStatus.UNDELIVERED);
            assertThat(report.detail()).isEqualTo("Fail");
        });
    }

    @Test
    @DisplayName("SG-03: code 6 Unknown produces no command and is left to the reconciliation")
    void unknownCodeProducesNoCommand() {
        // Act
        List<ProviderStatusCommand> reports = translator.translate("{\"id\":\"1\",\"code\":\"6\"}", Map.of());

        // Assert
        assertThat(reports).isEmpty();
    }

    @Test
    @DisplayName("§18.2 code 7: a blacklisted number is reported as undelivered, not as rejected")
    void blacklistBecomesUndelivered() {
        // Act
        ProviderStatusCommand report =
                translator.translate("{\"id\":\"1\",\"code\":\"7\"}", Map.of()).getFirst();

        // Assert
        assertThat(report.status()).isEqualTo(MessageStatus.UNDELIVERED);
        assertThat(report.providerStatus()).isEqualTo("InBlackList");
    }

    @Test
    @DisplayName("SG-02: a report without an id or without a code is refused with the field named")
    void refusesIncompleteReports() {
        // Act + Assert
        assertThatThrownBy(() -> translator.translate("{\"code\":\"4\"}", Map.of()))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> translator.translate("{\"id\":\"1\"}", Map.of()))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("SEC-07: a report with neither a body nor parameters is refused")
    void refusesEmptyReport() {
        // Act + Assert
        assertThatThrownBy(() -> translator.translate(null, Map.of())).isInstanceOf(InboundContractException.class);
    }
}
