package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsMessage;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.AuditEntryView;
import uz.hamkorbank.commhub.application.mapper.AuditMapperImpl;
import uz.hamkorbank.commhub.application.mapper.MessageMapperImpl;
import uz.hamkorbank.commhub.application.port.in.query.AuditQuery;
import uz.hamkorbank.commhub.application.port.in.query.MessageQuery;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.AuditQueryPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.service.support.PersonalDataAccess;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Message;

/** SEC-08 and FR-7.3: who looked at a customer's message, and how the journal is read back. */
class AuditAccessTest {

    private AuditPort audit;
    private MessageRepository messages;
    private MessageQueryService queries;

    @BeforeEach
    void setUp() {
        audit = mock(AuditPort.class);
        messages = mock(MessageRepository.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        queries = new MessageQueryService(messages, new PersonalDataAccess(audit, clock), new MessageMapperImpl());
    }

    @Test
    @DisplayName("SEC-08: an operator opening a customer's message is journalled")
    void journalsAnOperatorsLookup() {
        // Arrange
        Message message = smsMessage();
        when(messages.findById(message.id())).thenReturn(Optional.of(message));

        // Act
        queries.get(MessageQuery.byId(message.id(), Actor.operator("i.petrov")));

        // Assert
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo(PersonalDataAccess.ACTION_VIEW);
        assertThat(entry.getValue().entityId()).isEqualTo(message.id().toString());
        assertThat(entry.getValue().actor().id()).isEqualTo("i.petrov");
    }

    @Test
    @DisplayName("a source system polling its own submission is not: that would be a row per poll")
    void doesNotJournalMachinePolling() {
        // Arrange
        Message message = smsMessage();
        when(messages.findByExternalId(any(), any())).thenReturn(Optional.of(message));

        // Act
        queries.get(MessageQuery.byExternalId(
                STREAM_ID, message.envelope().externalId(), Actor.sourceSystem(STREAM_ID.value())));
        queries.get(MessageQuery.byExternalId(STREAM_ID, message.envelope().externalId()));

        // Assert
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("FR-7.3: the journal is read back with the name the action was performed under")
    void readsTheJournalBack() {
        // Arrange
        AuditQueryPort journal = mock(AuditQueryPort.class);
        when(journal.search(any()))
                .thenReturn(List.of(new AuditEntry(
                        Actor.operator("a.karimov"),
                        "provider.disabled",
                        "provider",
                        "PLAYMOBILE",
                        "enabled=true",
                        "enabled=false",
                        "10.1.2.3",
                        NOW)));
        when(journal.count(any())).thenReturn(1L);
        AuditQueryService service = new AuditQueryService(journal, new AuditMapperImpl());

        // Act
        List<AuditEntryView> page = service.list(AuditQuery.ofEntity("provider", "PLAYMOBILE"));

        // Assert
        assertThat(service.count(AuditQuery.firstPage())).isEqualTo(1);
        assertThat(page).singleElement().satisfies(view -> {
            assertThat(view.username()).isEqualTo("a.karimov");
            assertThat(view.action()).isEqualTo("provider.disabled");
            assertThat(view.before()).isEqualTo("enabled=true");
            assertThat(view.after()).isEqualTo("enabled=false");
            assertThat(view.sourceIp()).isEqualTo("10.1.2.3");
        });
    }

    @Test
    @DisplayName("an entry written by the Hub itself is readable as SYSTEM, not as a missing user")
    void rendersTheSystemActor() {
        // Arrange
        AuditQueryPort journal = mock(AuditQueryPort.class);
        when(journal.search(any()))
                .thenReturn(List.of(AuditEntry.of(Actor.system(), "batch.expired", "batch", "b-1", NOW)));

        // Act
        List<AuditEntryView> page = new AuditQueryService(journal, new AuditMapperImpl()).list(AuditQuery.firstPage());

        // Assert
        assertThat(page).singleElement().extracting(AuditEntryView::username).isEqualTo("SYSTEM");
    }
}
