package uz.hamkorbank.commhub.adapter.in.callback;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.hamkorbank.commhub.adapter.in.callback.CallbackProperties.Provider;
import uz.hamkorbank.commhub.adapter.in.callback.handlers.CallbackAuthenticationHandler;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.NotFoundHandler;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.port.in.ProcessProviderStatus;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;

/** The provider webhook: who may call it and what happens to the report (PM-02, SEC-07). */
@ExtendWith(MockitoExtension.class)
class ProviderCallbackControllerTest {

    private static final String PROVIDER = "PLAYMOBILE";

    private static final String SECRET = "s3cr3t-agreed-with-the-provider";

    private static final String PATH = CallbackProperties.DEFAULT_BASE + "/" + PROVIDER;

    @Mock
    private ProcessProviderStatus processStatus;

    @Test
    @DisplayName("PM-02: an authenticated report reaches the use case and is acknowledged with 200")
    void appliesAnAuthenticatedReport() throws Exception {
        // Arrange
        when(processStatus.process(any()))
                .thenReturn(ProcessProviderStatusResult.applied(MessageId.newId(), MessageStatus.DELIVERED));
        MockMvc mockMvc = mockMvc(provider(List.of(), SECRET), new StubTranslator(2));

        // Act + Assert
        mockMvc.perform(post(PATH)
                        .header(Provider.DEFAULT_SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.applied").value(2));
        verify(processStatus, times(2)).process(any(ProviderStatusCommand.class));
    }

    @Test
    @DisplayName("AD-06: a repeated report that changes nothing is still answered 200")
    void acknowledgesAReportThatChangedNothing() throws Exception {
        // Arrange
        when(processStatus.process(any()))
                .thenReturn(ProcessProviderStatusResult.unknownMessage("no message with that provider id"));
        MockMvc mockMvc = mockMvc(provider(List.of(), SECRET), new StubTranslator(1));

        // Act + Assert
        mockMvc.perform(post(PATH)
                        .header(Provider.DEFAULT_SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
    }

    @Test
    @DisplayName("SEC-07: a wrong secret is refused with a bare 403 and never reaches the use case")
    void refusesAWrongSecret() throws Exception {
        // Arrange
        MockMvc mockMvc = mockMvc(provider(List.of(), SECRET), new StubTranslator(1));

        // Act + Assert
        mockMvc.perform(post(PATH)
                        .header(Provider.DEFAULT_SECRET_HEADER, "guessed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("callback refused"));
        verify(processStatus, never()).process(any());
    }

    @Test
    @DisplayName("SEC-07: an address outside the allowlist is refused")
    void refusesAnAddressOutsideTheAllowlist() throws Exception {
        // Arrange
        MockMvc mockMvc = mockMvc(provider(List.of("10.10.10.10"), null), new StubTranslator(1));

        // Act + Assert
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-07: the allowlist honours the proxy header of the contour")
    void acceptsAnAllowlistedForwardedAddress() throws Exception {
        // Arrange
        when(processStatus.process(any()))
                .thenReturn(ProcessProviderStatusResult.applied(MessageId.newId(), MessageStatus.DELIVERED));
        MockMvc mockMvc = mockMvc(provider(List.of("10.10.10.10"), null), new StubTranslator(1));

        // Act + Assert
        mockMvc.perform(post(PATH)
                        .header("X-Forwarded-For", "10.10.10.10, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A provider nobody configured is refused, not silently accepted")
    void refusesAnUnconfiguredProvider() throws Exception {
        // Arrange
        MockMvc mockMvc = mockMvc(Map.of(), new StubTranslator(1));

        // Act + Assert
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AR-04: a configured provider without a translator answers 404 until its adapter ships")
    void answers404WithoutATranslator() throws Exception {
        // Arrange
        MockMvc mockMvc = mockMvc(provider(List.of(), SECRET));

        // Act + Assert
        mockMvc.perform(post(PATH)
                        .header(Provider.DEFAULT_SECRET_HEADER, SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private MockMvc mockMvc(Map<String, Provider> providers, ProviderCallbackTranslator... translators) {
        CallbackProperties properties = new CallbackProperties(null, providers);
        ProviderCallbackController controller = new ProviderCallbackController(
                new CallbackGuard(properties), new ProviderCallbackRegistry(List.of(translators)), processStatus);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new CallbackAuthenticationHandler(), new NotFoundHandler(new ProblemFactory()))
                .build();
    }

    private static Map<String, Provider> provider(List<String> allowedIps, String secret) {
        return Map.of(PROVIDER, new Provider(true, allowedIps, null, secret));
    }

    /** Stands in for the Playmobile and SMS Gate translators, which live with their adapters. */
    private record StubTranslator(int reports) implements ProviderCallbackTranslator {

        @Override
        public ProviderCode providerCode() {
            return ProviderCode.of(PROVIDER);
        }

        @Override
        public List<ProviderStatusCommand> translate(String body, Map<String, String> parameters) {
            return java.util.stream.IntStream.range(0, reports)
                    .mapToObj(index -> ProviderStatusCommand.of(
                            providerCode(),
                            ProviderMessageId.of("HB" + index),
                            MessageStatus.DELIVERED,
                            "DELIVRD",
                            Instant.parse("2026-08-08T09:00:00Z")))
                    .toList();
        }
    }
}
