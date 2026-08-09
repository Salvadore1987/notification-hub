package uz.hamkorbank.commhub.adapter.out.provider.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;

/** What may reach a log line from the one layer that handles one-time passwords (PR-03, OBS-03). */
class MaskingTest {

    @Test
    @DisplayName("DB-04: a number keeps its shape and loses its middle")
    void masksMsisdn() {
        // Act
        String masked = Masking.msisdn(Msisdn.of("998901234567"));

        // Assert
        assertThat(masked).isEqualTo("99890***4567");
    }

    @Test
    @DisplayName("OBS-03: an unparsed number from a callback is masked the same way")
    void masksRawMsisdn() {
        // Act + Assert
        assertThat(Masking.msisdn("998901234567")).isEqualTo("99890***4567");
        assertThat(Masking.msisdn("12345")).isEqualTo("***");
        assertThat(Masking.msisdn((String) null)).isEqualTo("-");
    }

    @Test
    @DisplayName("SEC-06: message text is reduced to its length, keeping no prefix")
    void textKeepsOnlyItsLength() {
        // Act
        String masked = Masking.text("Kod: 4821. Nikomu ne soobshchayte.");

        // Assert
        assertThat(masked).isEqualTo("[34 chars]");
        assertThat(masked).doesNotContain("4821").doesNotContain("Kod");
    }

    @Test
    @DisplayName("EM-01, OBS-03: an email keeps its domain and loses its local part")
    void emailIsMaskedButStaysDiagnosable() {
        // Act + Assert
        assertThat(Masking.email(EmailAddress.of("ivan.petrov@example.com"))).isEqualTo("i***v@example.com");
        // Домен переживает маскирование намеренно: «всё на этот домен отбивается» — то, ради чего
        // журнал bounce'ов и читают, и персональных данных в домене нет.
        assertThat(Masking.email("po@example.com")).isEqualTo("p***@example.com");
        assertThat(Masking.email("not-an-address")).isEqualTo("***");
        assertThat(Masking.email((String) null)).isEqualTo("-");
    }

    @Test
    @DisplayName("SEC-04: a credential renders as nothing at all")
    void secretRendersAsRedacted() {
        // Act + Assert
        assertThat(Masking.secret("s3cr3t")).isEqualTo("***");
        assertThat(Masking.secret(" ")).isEqualTo("-");
    }

    @Test
    @DisplayName("PR-03: a response body is flattened and truncated before it is logged")
    void bodyIsFlattenedAndTruncated() {
        // Act
        String masked = Masking.body("{\n  \"error-code\": 102,\n  \"error-description\": \"Account lock\"\n}", 20);

        // Assert
        assertThat(masked).doesNotContain("\n").hasSize(21).endsWith("…");
    }
}
