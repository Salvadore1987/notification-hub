package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.msisdn;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.SuppressionCheckView;
import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.SuppressionMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.ReleaseSuppressionCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressAddressCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressClientCommand;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionCheckQuery;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionQuery;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;

/** Administration and lookup of the suppression list (FR-5.1, FR-7.3). */
class SuppressionUseCasesTest {

    private static final Actor OPERATOR = Actor.operator("a.karimov");
    private static final ClientId CLIENT = ClientId.of("CL-42");

    private SuppressionRepository suppressions;
    private AuditPort audit;
    private SuppressionService service;
    private SuppressionQueryService queries;

    @BeforeEach
    void setUp() {
        suppressions = mock(SuppressionRepository.class);
        audit = mock(AuditPort.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(suppressions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(suppressions.findByAddress(any(), any())).thenReturn(Optional.empty());
        when(suppressions.findByClient(any(), any())).thenReturn(Optional.empty());
        SuppressionMapperImpl mapper = new SuppressionMapperImpl();
        service = new SuppressionService(suppressions, clock, mapper, new ConfigAuditor(audit, clock));
        queries = new SuppressionQueryService(suppressions, clock, mapper);
    }

    @Test
    @DisplayName("FR-5.1, DB-04: an address is stored as its hash, never as the number itself")
    void storesAddressAsHash() {
        // Arrange
        SuppressAddressCommand command =
                SuppressAddressCommand.of(OPERATOR, Channel.SMS, "+998 90 123-45-67", SuppressionReason.COMPLAINT);

        // Act
        SuppressionView view = service.suppressAddress(command);

        // Assert
        assertThat(view.addressHash()).isEqualTo(AddressHash.ofMsisdn(msisdn()));
        assertThat(view.reason()).isEqualTo(SuppressionReason.COMPLAINT);
        assertThat(view.clientId()).isNull();
        SuppressionEntry saved = savedEntry();
        assertThat(saved.addressHash()).contains(AddressHash.ofMsisdn(msisdn()));
        assertThat(saved.createdBy()).contains("a.karimov");
    }

    @Test
    @DisplayName("FR-1.4: a mistyped address is refused instead of becoming a hash that matches nothing")
    void refusesMalformedAddress() {
        // Arrange
        SuppressAddressCommand command =
                SuppressAddressCommand.of(OPERATOR, Channel.SMS, "0901234567", SuppressionReason.OPT_OUT);

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> service.suppressAddress(command));
        verify(suppressions, never()).save(any());
    }

    @Test
    @DisplayName("FR-5.1: a client can be banned on every channel at once")
    void suppressesClientAcrossChannels() {
        // Arrange
        SuppressClientCommand command = SuppressClientCommand.of(OPERATOR, CLIENT, SuppressionReason.OPT_OUT);

        // Act
        SuppressionView view = service.suppressClient(command);

        // Assert
        assertThat(view.channel()).isNull();
        assertThat(view.clientId()).isEqualTo(CLIENT);
        assertThat(savedEntry().coversChannel(Channel.PUSH)).isTrue();
    }

    @Test
    @DisplayName("FR-5.1: a temporary ban carries its expiry and stops applying afterwards")
    void supportsTemporaryBan() {
        // Arrange
        SuppressClientCommand command = new SuppressClientCommand(
                OPERATOR, Channel.SMS, CLIENT, SuppressionReason.DELIVERY_FAILURES, NOW.plus(Duration.ofDays(7)));

        // Act
        service.suppressClient(command);

        // Assert
        SuppressionEntry saved = savedEntry();
        assertThat(saved.isActiveAt(NOW.plus(Duration.ofDays(1)))).isTrue();
        assertThat(saved.isActiveAt(NOW.plus(Duration.ofDays(8)))).isFalse();
    }

    @Test
    @DisplayName("FR-5.1: listing the same target twice is a conflict, not a second row")
    void refusesDuplicateEntry() {
        // Arrange
        when(suppressions.findByAddress(any(), any())).thenReturn(Optional.of(addressEntry()));
        SuppressAddressCommand command =
                SuppressAddressCommand.of(OPERATOR, Channel.SMS, msisdn().value(), SuppressionReason.OPT_OUT);

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> service.suppressAddress(command))
                .withMessageContaining("already suppressed");
        verify(suppressions, never()).save(any());
    }

    @Test
    @DisplayName("FR-7.3: adding somebody to the list and taking them off it are both audited")
    void auditsEveryChange() {
        // Arrange
        SuppressionEntry entry = addressEntry();
        when(suppressions.findById(entry.id())).thenReturn(Optional.of(entry));

        // Act
        service.suppressClient(SuppressClientCommand.of(OPERATOR, CLIENT, SuppressionReason.OPT_OUT));
        service.release(new ReleaseSuppressionCommand(OPERATOR, entry.id(), "client opted back in"));

        // Assert
        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, times(2)).write(entries.capture());
        assertThat(entries.getAllValues())
                .extracting(AuditEntry::action)
                .containsExactly("suppression.add", "suppression.release");
        assertThat(entries.getAllValues().getLast().reason()).isEqualTo("client opted back in");
        verify(suppressions).delete(entry.id());
    }

    @Test
    @DisplayName("FR-7.3: the Hub itself does not edit the list through the administration use case")
    void refusesNamelessActor() {
        // Arrange
        SuppressClientCommand command = SuppressClientCommand.of(Actor.system(), CLIENT, SuppressionReason.MANUAL);

        // Act + Assert
        assertThatExceptionOfType(ConfigurationConflictException.class)
                .isThrownBy(() -> service.suppressClient(command))
                .withMessageContaining("named actor");
    }

    @Test
    @DisplayName("FR-5.1: releasing an entry that is not there is a 404, not a silent success")
    void refusesUnknownEntryOnRelease() {
        // Arrange
        SuppressionEntryId unknown = SuppressionEntryId.newId();
        when(suppressions.findById(unknown)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> service.release(new ReleaseSuppressionCommand(OPERATOR, unknown, null)));
    }

    @Test
    @DisplayName("FR-5.1: the check hashes the address and answers with the entry in force")
    void checksAddressBeforeSending() {
        // Arrange
        AddressHash hash = AddressHash.ofMsisdn(msisdn());
        when(suppressions.findActiveByAddress(hash, Channel.SMS, NOW)).thenReturn(Optional.of(addressEntry()));

        // Act
        SuppressionCheckView view = queries.check(SuppressionCheckQuery.ofAddress(Channel.SMS, msisdn().value()));

        // Assert
        assertThat(view.suppressed()).isTrue();
        assertThat(view.entryOptional().orElseThrow().reason()).isEqualTo(SuppressionReason.HARD_BOUNCE);
    }

    @Test
    @DisplayName("FR-5.1: a client-wide ban answers the check even when the address itself is clean")
    void checksClientWhenAddressIsClean() {
        // Arrange
        when(suppressions.findActiveByAddress(any(), any(), any())).thenReturn(Optional.empty());
        when(suppressions.findActiveByClient(CLIENT, Channel.SMS, NOW))
                .thenReturn(Optional.of(SuppressionEntry.forClient(
                        SuppressionEntryId.newId(), null, CLIENT, SuppressionReason.OPT_OUT, NOW, "a.karimov")));

        // Act
        SuppressionCheckView view = queries.check(new SuppressionCheckQuery(Channel.SMS, msisdn().value(), CLIENT));

        // Assert
        assertThat(view.suppressed()).isTrue();
        assertThat(view.entryOptional().orElseThrow().clientId()).isEqualTo(CLIENT);
    }

    @Test
    @DisplayName("FR-5.1: a recipient nobody listed is allowed")
    void allowsUnlistedRecipient() {
        // Arrange
        when(suppressions.findActiveByAddress(any(), any(), any())).thenReturn(Optional.empty());

        // Act
        SuppressionCheckView view = queries.check(SuppressionCheckQuery.ofAddress(Channel.SMS, msisdn().value()));

        // Assert
        assertThat(view.suppressed()).isFalse();
        assertThat(view.entry()).isNull();
    }

    @Test
    @DisplayName("UI-03: the listing passes its filters and page through to the repository")
    void listsPageWithFilters() {
        // Arrange
        when(suppressions.findAll(Channel.SMS, SuppressionReason.HARD_BOUNCE, null, 50, 0))
                .thenReturn(List.of(addressEntry()));

        // Act
        List<SuppressionView> page =
                queries.list(new SuppressionQuery(Channel.SMS, SuppressionReason.HARD_BOUNCE, null, 50, 0));

        // Assert
        assertThat(page).hasSize(1);
        assertThat(page.getFirst().channel()).isEqualTo(Channel.SMS);
    }

    private SuppressionEntry addressEntry() {
        return SuppressionEntry.forAddress(
                SuppressionEntryId.newId(),
                Channel.SMS,
                AddressHash.ofMsisdn(msisdn()),
                SuppressionReason.HARD_BOUNCE,
                NOW,
                "a.karimov");
    }

    private SuppressionEntry savedEntry() {
        ArgumentCaptor<SuppressionEntry> captor = ArgumentCaptor.forClass(SuppressionEntry.class);
        verify(suppressions).save(captor.capture());
        return captor.getValue();
    }
}
