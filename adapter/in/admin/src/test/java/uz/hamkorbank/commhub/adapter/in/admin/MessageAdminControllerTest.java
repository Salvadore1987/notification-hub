package uz.hamkorbank.commhub.adapter.in.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapperImpl;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminMasking;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPeriod;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.ContractViolationHandler;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapperImpl;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.adapter.in.rest.security.Roles;
import uz.hamkorbank.commhub.application.dto.MessageDigestView;
import uz.hamkorbank.commhub.application.port.in.GetMessage;
import uz.hamkorbank.commhub.application.port.in.SearchMessages;
import uz.hamkorbank.commhub.application.port.in.query.MessageSearchQuery;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** {@code /api/admin/v1/messages} (§11.2 "Сообщения", UI-03, DB-04). */
@ExtendWith(MockitoExtension.class)
class MessageAdminControllerTest {

    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    private static final String MSISDN = "998901234567";

    @Mock
    private SearchMessages searchMessages;

    @Mock
    private GetMessage getMessage;

    @Mock
    private ClockPort clock;

    @Mock
    private AuthenticatedCaller caller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        lenient().when(clock.now()).thenReturn(NOW);
        mockMvc = MockMvcBuilders.standaloneSetup(new MessageAdminController(
                        searchMessages,
                        getMessage,
                        new AdminPeriod(clock),
                        new AdminMasking(caller),
                        caller,
                        new AdminViewMapperImpl(),
                        new RestResponseMapperImpl()))
                .setControllerAdvice(new ContractViolationHandler(new ProblemFactory()))
                .build();
    }

    @Test
    @DisplayName("§11.2: an operator sees the address in full, and the page carries the total")
    void operatorSeesTheFullAddress() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        when(caller.hasAnyRole(Roles.ADMIN, Roles.OPERATOR)).thenReturn(true);
        when(searchMessages.search(any())).thenReturn(List.of(digest()));
        when(searchMessages.count(any())).thenReturn(137L);

        // Act + Assert
        mockMvc.perform(get(AdminApi.MESSAGES).param("recipient", MSISDN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(137))
                .andExpect(jsonPath("$.limit").value(MessageSearchQuery.DEFAULT_LIMIT))
                .andExpect(jsonPath("$.items[0].recipient").value(MSISDN))
                .andExpect(jsonPath("$.items[0].status").value("DELIVERED"));
    }

    @Test
    @DisplayName("DB-04: a viewer gets the same rows with the address masked")
    void viewerSeesAmaskedAddress() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("v.viewer"));
        when(caller.hasAnyRole(Roles.ADMIN, Roles.OPERATOR)).thenReturn(false);
        when(searchMessages.search(any())).thenReturn(List.of(digest()));
        when(searchMessages.count(any())).thenReturn(1L);

        // Act + Assert
        mockMvc.perform(get(AdminApi.MESSAGES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recipient").value("99890***4567"));
    }

    @Test
    @DisplayName("SEC-08: the query carries the authenticated actor, so the use case can journal the read")
    void queryCarriesTheActor() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        when(searchMessages.search(any())).thenReturn(List.of());
        when(searchMessages.count(any())).thenReturn(0L);

        // Act
        mockMvc.perform(get(AdminApi.MESSAGES)
                        .param("streamId", "core-banking")
                        .param("correlationId", "corr-1")
                        .param("limit", "10"))
                .andExpect(status().isOk());

        // Assert
        ArgumentCaptor<MessageSearchQuery> query = ArgumentCaptor.forClass(MessageSearchQuery.class);
        verify(searchMessages).search(query.capture());
        assertThat(query.getValue().requestedBy().id()).isEqualTo("a.karimov");
        assertThat(query.getValue().streamId().value()).isEqualTo("core-banking");
        assertThat(query.getValue().filter().correlationId()).isEqualTo("corr-1");
        assertThat(query.getValue().limit()).isEqualTo(10);
        assertThat(query.getValue().from()).isEqualTo(NOW.minus(AdminPeriod.DEFAULT_WINDOW));
    }

    @Test
    @DisplayName("IR-01: a malformed status is a problem+json naming the field, not a 500")
    void malformedFilterIsAproblemDocument() throws Exception {
        // Act + Assert
        mockMvc.perform(get(AdminApi.MESSAGES).param("status", "SENT_MAYBE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("status"));
    }

    @Test
    @DisplayName("IR-01: a message id that is not a UUID is refused at the edge")
    void malformedMessageIdIsRefused() throws Exception {
        // Act + Assert
        mockMvc.perform(get(AdminApi.MESSAGES + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("messageId"));
    }

    private static MessageDigestView digest() {
        return new MessageDigestView(
                MessageId.newId(),
                StreamId.of("core-banking"),
                ExternalMessageId.of("EXT-1"),
                Channel.SMS,
                MessageStatus.DELIVERED,
                MSISDN,
                NOW,
                new MessageDigestView.Routing(null, Channel.SMS, null, null, CorrelationId.of("corr-1"), null, 1, NOW));
    }
}
