package uz.hamkorbank.commhub.adapter.in.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapperImpl;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapperImpl;
import uz.hamkorbank.commhub.adapter.in.admin.support.RecipientListCsvCodec;
import uz.hamkorbank.commhub.adapter.in.admin.support.SendLimits;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.SubmissionRejectionHandler;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapperImpl;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.EstimateSend;
import uz.hamkorbank.commhub.application.port.in.SendOperatorMessage;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/**
 * {@code /api/admin/v1/send} (§11.2 "Отправка", ADR-0038).
 *
 * <p>The one thing pinned here is that a refusal leaves as a problem document. It used to leave as
 * {@code 200} with {@code "status":"REJECTED"} in the body (D-12), so an exhausted quota reached the
 * operator as a success they had to read the body to disbelieve.
 */
@ExtendWith(MockitoExtension.class)
class SendAdminControllerTest {

    private static final String BODY = """
            {"streamId":"marketing","templateCode":"PROMO","locale":"RU","channel":"SMS",
             "recipient":{"msisdn":"998901234500"}}""";

    @Mock
    private SendOperatorMessage sendMessage;

    @Mock
    private EstimateSend estimates;

    @Mock
    private AuthenticatedCaller caller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SendAdminController(
                        sendMessage,
                        estimates,
                        new RecipientListCsvCodec(),
                        caller,
                        new AdminCommandMapperImpl(),
                        new AdminViewMapperImpl(),
                        new RestResponseMapperImpl(),
                        SendLimits.defaults()))
                .setControllerAdvice(new SubmissionRejectionHandler(new ProblemFactory()))
                .build();
    }

    @Test
    @DisplayName("FR-2.6: исчерпанная квота приходит как 429 problem+json, а не как 200 с REJECTED")
    void refusalIsAProblemDocument() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        MessageId messageId = MessageId.newId();
        when(sendMessage.send(any()))
                .thenReturn(SubmitMessageResult.rejected(
                        messageId, RejectionReason.QUOTA_EXCEEDED, "quota of stream marketing is exhausted"));

        // Act + Assert
        mockMvc.perform(post(AdminApi.SEND + "/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminApi.REASON_HEADER, "campaign check")
                        .content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value("quota of stream marketing is exhausted"))
                .andExpect(jsonPath("$.messageId").value(messageId.toString()));
    }

    @Test
    @DisplayName("принятое сообщение отвечает 200 и своим идентификатором")
    void acceptedStaysAnOrdinaryAnswer() throws Exception {
        // Arrange
        when(caller.actor()).thenReturn(Actor.operator("a.karimov"));
        MessageId messageId = MessageId.newId();
        when(sendMessage.send(any())).thenReturn(SubmitMessageResult.accepted(messageId, MessageStatus.QUEUED));

        // Act + Assert
        mockMvc.perform(post(AdminApi.SEND + "/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(AdminApi.REASON_HEADER, "campaign check")
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }
}
