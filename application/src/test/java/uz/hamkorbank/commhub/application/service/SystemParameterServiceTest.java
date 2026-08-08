package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.hamkorbank.commhub.application.dto.SystemParameterView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.SystemParameterMapperImpl;
import uz.hamkorbank.commhub.application.port.in.command.SetSystemParameterCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SystemParameter;
import uz.hamkorbank.commhub.application.port.out.SystemParameterPort;
import uz.hamkorbank.commhub.domain.model.Actor;

/** System parameters of §11.2 "Администрирование" (NF-06, FR-7.3). */
class SystemParameterServiceTest {

    private static final String KEY = "dashboard.banner";

    private static final Actor OPERATOR = Actor.operator("a.karimov");

    private ClockPort clock;
    private SystemParameterPort parameters;
    private AuditPort audit;
    private SystemParameterService service;

    @BeforeEach
    void setUp() {
        clock = mock(ClockPort.class);
        parameters = mock(SystemParameterPort.class);
        audit = mock(AuditPort.class);
        when(clock.now()).thenReturn(NOW);
        service = new SystemParameterService(clock, parameters, audit, new SystemParameterMapperImpl());
    }

    @Test
    @DisplayName("FR-7.3: writing a parameter journals the value it had before")
    void writeJournalsBeforeAndAfter() {
        // Arrange
        when(parameters.find(KEY)).thenReturn(Optional.of(parameter("maintenance tonight")));
        when(parameters.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        SystemParameterView view =
                service.set(new SetSystemParameterCommand(KEY, "all clear", "Banner text", OPERATOR));

        // Assert
        assertThat(view.value()).isEqualTo("all clear");
        assertThat(view.updatedBy()).isEqualTo("a.karimov");
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("system-parameter.update");
        assertThat(entry.getValue().before()).isEqualTo("maintenance tonight");
        assertThat(entry.getValue().after()).isEqualTo("all clear");
    }

    @Test
    @DisplayName("NF-06: a new key is journalled as a creation and keeps the description it was given")
    void newKeyIsAcreation() {
        // Arrange
        when(parameters.find(KEY)).thenReturn(Optional.empty());
        when(parameters.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        service.set(new SetSystemParameterCommand(KEY, "hello", "Banner text", OPERATOR));

        // Assert
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("system-parameter.create");
        assertThat(entry.getValue().before()).isNull();
    }

    @Test
    @DisplayName("NF-06: an update without a description keeps the one that was already there")
    void updateKeepsTheExistingDescription() {
        // Arrange
        when(parameters.find(KEY)).thenReturn(Optional.of(parameter("old")));
        ArgumentCaptor<SystemParameter> saved = ArgumentCaptor.forClass(SystemParameter.class);
        when(parameters.save(saved.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        service.set(new SetSystemParameterCommand(KEY, "new", null, OPERATOR));

        // Assert
        assertThat(saved.getValue().description()).isEqualTo("Banner text");
    }

    @Test
    @DisplayName("§11.2: removing a key that is not there is a 404, not a silent success")
    void removingAnUnknownKeyIsNotFound() {
        // Arrange
        when(parameters.find(KEY)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.remove(SetSystemParameterCommand.remove(KEY, OPERATOR)))
                .isInstanceOf(NotFoundException.class);
        verify(parameters, never()).delete(any());
        verify(audit, never()).write(any());
    }

    @Test
    @DisplayName("FR-7.3: removing a parameter journals the value that was lost")
    void removalJournalsTheOldValue() {
        // Arrange
        when(parameters.find(KEY)).thenReturn(Optional.of(parameter("maintenance tonight")));

        // Act
        service.remove(SetSystemParameterCommand.remove(KEY, OPERATOR));

        // Assert
        verify(parameters).delete(KEY);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(entry.capture());
        assertThat(entry.getValue().action()).isEqualTo("system-parameter.delete");
        assertThat(entry.getValue().before()).isEqualTo("maintenance tonight");
        assertThat(entry.getValue().after()).isNull();
    }

    private static SystemParameter parameter(String value) {
        return new SystemParameter(KEY, value, "Banner text", NOW, "s.usmanov");
    }
}
