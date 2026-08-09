package uz.hamkorbank.commhub.adapter.in.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapperImpl;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.ContractViolationHandler;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.KillSwitchResult;
import uz.hamkorbank.commhub.application.dto.SystemParameterView;
import uz.hamkorbank.commhub.application.port.in.KillSwitch;
import uz.hamkorbank.commhub.application.port.in.ManageSystemParameters;
import uz.hamkorbank.commhub.application.port.in.command.KillSwitchCommand;
import uz.hamkorbank.commhub.application.port.in.command.SetSystemParameterCommand;
import uz.hamkorbank.commhub.domain.model.Actor;

/** {@code /api/admin/v1/administration} (§11.2 "Администрирование", FR-3.2, NF-06). */
@ExtendWith(MockitoExtension.class)
class AdministrationControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    @Mock
    private KillSwitch killSwitch;

    @Mock
    private ManageSystemParameters parameters;

    @Mock
    private AuthenticatedCaller caller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdministrationController(killSwitch, parameters, caller, new AdminViewMapperImpl()))
                .setControllerAdvice(new ContractViolationHandler(new ProblemFactory()))
                .build();
    }

    @Test
    @DisplayName("FR-3.2: activating the kill switch carries the actor and the reason into the command")
    void activationCarriesActorAndReason() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        when(killSwitch.apply(any())).thenReturn(new KillSwitchResult(true, false, NOW));

        // Act
        mockMvc.perform(post(AdminApi.ADMINISTRATION + "/kill-switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activate\":true,\"reason\":\"provider outage\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.includesCriticalOtp").value(false));

        // Assert
        ArgumentCaptor<KillSwitchCommand> command = ArgumentCaptor.forClass(KillSwitchCommand.class);
        verify(killSwitch).apply(command.capture());
        assertThat(command.getValue().actor().id()).isEqualTo("a.karimov");
        assertThat(command.getValue().reason()).isEqualTo("provider outage");
        assertThat(command.getValue().includeCriticalOtp()).isFalse();
    }

    @Test
    @DisplayName("FR-7.3: activating without a reason is refused before the use case is reached")
    void activationWithoutAreasonIsRefused() throws Exception {
        // Act + Assert
        mockMvc.perform(post(AdminApi.ADMINISTRATION + "/kill-switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activate\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("reason"));
        verify(killSwitch, never()).apply(any());
    }

    @Test
    @DisplayName("FR-3.2: releasing the switch needs no reason")
    void releaseNeedsNoReason() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        when(killSwitch.apply(any())).thenReturn(new KillSwitchResult(false, false, NOW));

        // Act + Assert
        mockMvc.perform(post(AdminApi.ADMINISTRATION + "/kill-switch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activate\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("NF-06: a parameter is written with the key from the path and the actor from the token")
    void parameterTakesTheKeyFromThePath() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        when(parameters.set(any()))
                .thenReturn(new SystemParameterView("dashboard.banner", "hello", null, NOW, "a.karimov"));

        // Act
        mockMvc.perform(put(AdminApi.ADMINISTRATION + "/parameters/dashboard.banner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("dashboard.banner"));

        // Assert
        ArgumentCaptor<SetSystemParameterCommand> command = ArgumentCaptor.forClass(SetSystemParameterCommand.class);
        verify(parameters).set(command.capture());
        assertThat(command.getValue().key()).isEqualTo("dashboard.banner");
        assertThat(command.getValue().actor().id()).isEqualTo("a.karimov");
    }

    @Test
    @DisplayName("NF-06: a body with no value is refused rather than storing an empty parameter")
    void parameterWithoutAvalueIsRefused() throws Exception {
        // Act + Assert
        mockMvc.perform(put(AdminApi.ADMINISTRATION + "/parameters/dashboard.banner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("value"));
        verify(parameters, never()).set(any());
    }

    @Test
    @DisplayName("NF-06: the list and the removal reach their use cases")
    void listAndRemoval() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        when(parameters.list()).thenReturn(List.of(new SystemParameterView("a.key", "1", "desc", NOW, "a.karimov")));

        // Act + Assert
        mockMvc.perform(get(AdminApi.ADMINISTRATION + "/parameters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("a.key"))
                .andExpect(jsonPath("$[0].updatedBy").value("a.karimov"));
        mockMvc.perform(delete(AdminApi.ADMINISTRATION + "/parameters/a.key")).andExpect(status().isNoContent());
        verify(parameters).remove(any());
    }
}
