package uz.hamkorbank.commhub.adapter.in.admin.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.adapter.in.rest.security.Roles;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.type.Channel;

/** Periods, paging, masking and CSV: the decisions every admin endpoint shares (§11.2, UI-03). */
class AdminSupportTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    private ClockPort clock;
    private AdminPeriod period;

    @BeforeEach
    void setUp() {
        clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        period = new AdminPeriod(clock);
    }

    // ---------------------------------------------------------------- period

    @Test
    @DisplayName("DB-02: a screen opened without a period gets the last day rather than every partition")
    void defaultsToTheLastDay() {
        // Arrange + Act
        AdminPeriod.Period resolved = period.resolve(null, null);

        // Assert
        assertThat(resolved.to()).isEqualTo(NOW);
        assertThat(resolved.from()).isEqualTo(NOW.minus(AdminPeriod.DEFAULT_WINDOW));
    }

    @Test
    @DisplayName("UI-03: a period wider than the ceiling is refused rather than run")
    void refusesAnOversizedPeriod() {
        // Arrange
        String from =
                NOW.minus(AdminPeriod.MAX_WINDOW).minus(Duration.ofDays(1)).toString();

        // Act + Assert
        assertThatThrownBy(() -> period.resolve(from, NOW.toString()))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("exceeds the maximum");
    }

    @Test
    @DisplayName("IR-01: a reversed or unparseable period is a field-pointed refusal")
    void refusesABadPeriod() {
        // Act + Assert
        assertThatThrownBy(() -> period.resolve(
                        NOW.toString(), NOW.minus(Duration.ofHours(1)).toString()))
                .isInstanceOf(InboundContractException.class)
                .satisfies(
                        e -> assertThat(((InboundContractException) e).field()).isEqualTo("to"));
        assertThatThrownBy(() -> period.resolve("yesterday", null))
                .isInstanceOf(InboundContractException.class)
                .satisfies(
                        e -> assertThat(((InboundContractException) e).field()).isEqualTo("from"));
    }

    // ---------------------------------------------------------------- paging

    @Test
    @DisplayName("UI-03: paging defaults when absent and is refused when out of range")
    void pagingIsBounded() {
        // Act + Assert
        assertThat(AdminPaging.limit(null, 50, 500)).isEqualTo(50);
        assertThat(AdminPaging.limit(120, 50, 500)).isEqualTo(120);
        assertThat(AdminPaging.offset(null)).isZero();
        assertThatThrownBy(() -> AdminPaging.limit(0, 50, 500)).isInstanceOf(InboundContractException.class);
        assertThatThrownBy(() -> AdminPaging.limit(5_000, 50, 500)).isInstanceOf(InboundContractException.class);
        assertThatThrownBy(() -> AdminPaging.offset(-1)).isInstanceOf(InboundContractException.class);
    }

    // ---------------------------------------------------------------- masking

    @Test
    @DisplayName("§11.2: an operator sees the address in full and a viewer sees it masked")
    void masksByRole() {
        // Arrange
        AuthenticatedCaller operator = mock(AuthenticatedCaller.class);
        when(operator.hasAnyRole(Roles.ADMIN, Roles.OPERATOR)).thenReturn(true);
        AuthenticatedCaller viewer = mock(AuthenticatedCaller.class);
        when(viewer.hasAnyRole(Roles.ADMIN, Roles.OPERATOR)).thenReturn(false);

        // Act
        String forOperator = new AdminMasking(operator).recipient("998901234567");
        String maskedNumber = new AdminMasking(viewer).recipient("998901234567");
        String maskedEmail = new AdminMasking(viewer).recipient("islom.nazarov@example.com");

        // Assert
        assertThat(forOperator).isEqualTo("998901234567");
        assertThat(maskedNumber).isEqualTo("99890***4567");
        assertThat(maskedEmail).isEqualTo("i***v@example.com");
    }

    @Test
    @DisplayName("A row with no address stays empty rather than becoming a masked placeholder")
    void emptyAddressStaysEmpty() {
        // Arrange
        AuthenticatedCaller viewer = mock(AuthenticatedCaller.class);

        // Act + Assert
        assertThat(new AdminMasking(viewer).recipient(null)).isNull();
        assertThat(new AdminMasking(viewer).recipient("  ")).isNull();
    }

    // ---------------------------------------------------------------- CSV

    @Test
    @DisplayName("§11.2: the export is UTF-8 with a BOM and quotes what has to be quoted")
    void csvIsExcelReadable() {
        // Arrange
        List<List<String>> rows =
                List.of(List.of("SMS", "Код: 1234, срочно", "a\"b"), Arrays.asList("EMAIL", null, ""));

        // Act
        String csv = CsvRenderer.render(List.of("channel", "text", "note"), rows, row -> row);

        // Assert
        assertThat(csv).startsWith(CsvRenderer.BOM + "channel,text,note\r\n");
        assertThat(csv).contains("\"Код: 1234, срочно\"");
        assertThat(csv).contains("\"a\"\"b\"");
        assertThat(csv).contains("EMAIL,,\r\n");
    }

    @Test
    @DisplayName("A cell that starts like a formula is defused before Excel opens it")
    void csvDefusesFormulas() {
        // Arrange
        List<List<String>> rows = List.of(List.of("=1+1", "+79", "-5", "@sum"));

        // Act
        String csv = CsvRenderer.render(List.of("a", "b", "c", "d"), rows, row -> row);

        // Assert
        assertThat(csv).contains("'=1+1,'+79,'-5,'@sum");
    }

    // ---------------------------------------------------------------- value parsing

    @Test
    @DisplayName("IR-01: an unknown enum constant is refused with the list of the ones that exist")
    void unknownEnumIsRefused() {
        // Act + Assert
        assertThat(AdminValues.optionalEnum(Channel.class, "sms", "channel")).isEqualTo(Channel.SMS);
        assertThat(AdminValues.optionalEnum(Channel.class, null, "channel")).isNull();
        assertThatThrownBy(() -> AdminValues.optionalEnum(Channel.class, "telegram", "channel"))
                .isInstanceOf(InboundContractException.class)
                .hasMessageContaining("SMS");
        assertThatThrownBy(() -> AdminValues.requiredEnum(Channel.class, null, "channel"))
                .isInstanceOf(InboundContractException.class);
    }

    @Test
    @DisplayName("IR-01: a malformed identifier points at its field instead of failing deep in a use case")
    void malformedValuesPointAtTheirField() {
        // Act + Assert
        assertThatThrownBy(() -> AdminValues.requiredUuid("not-a-uuid", "messageId"))
                .isInstanceOf(InboundContractException.class)
                .satisfies(
                        e -> assertThat(((InboundContractException) e).field()).isEqualTo("messageId"));
        assertThatThrownBy(() -> AdminValues.instant("tomorrow", "from")).isInstanceOf(InboundContractException.class);
        assertThatThrownBy(() -> AdminValues.money("many", "tariff.perMessage"))
                .isInstanceOf(InboundContractException.class);
    }
}
