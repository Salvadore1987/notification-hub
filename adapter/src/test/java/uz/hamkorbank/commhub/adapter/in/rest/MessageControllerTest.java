package uz.hamkorbank.commhub.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.hamkorbank.commhub.adapter.in.contract.InboundJson;
import uz.hamkorbank.commhub.adapter.in.contract.InboundMessageCodec;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapperImpl;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.ContractViolationHandler;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.NotFoundHandler;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.RateLimitHandler;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.SubmissionRejectionHandler;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapperImpl;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.RateLimitProperties;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.StreamLimits;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.StreamRateLimiter;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.adapter.in.rest.security.SecurityProperties;
import uz.hamkorbank.commhub.adapter.in.rest.security.StreamAccessGuard;
import uz.hamkorbank.commhub.application.dto.MessageView;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.in.GetMessage;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * {@code /api/v1/messages} (§8.2, IR-01, IR-02).
 *
 * <p>Standalone MockMvc rather than a Spring slice: this module has no application context of its own,
 * and what is under test is the translation and the error rendering, both of which the standalone
 * setup exercises with the real advices in place.
 */
@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    private static final MessageId MESSAGE_ID = MessageId.newId();

    private static final String VALID_BODY = """
            {
              "streamId": "ibank-retail",
              "externalMessageId": "ibank-2026-000123",
              "recipient": { "msisdn": "998901234567" },
              "content": { "sms": { "text": "Kod: 123456" } }
            }
            """;

    @Mock
    private SubmitMessage submitMessage;

    @Mock
    private GetMessage getMessage;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcWith(limiterOf(RateLimitProperties.defaults()));
    }

    @Test
    @DisplayName("§8.2: an accepted message answers 202 with its identifier and a Location")
    void acceptsAMessage() throws Exception {
        // Arrange
        when(submitMessage.submit(any())).thenReturn(SubmitMessageResult.accepted(MESSAGE_ID, MessageStatus.QUEUED));

        // Act + Assert
        mockMvc.perform(post(ApiV1.MESSAGES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", ApiV1.MESSAGES + "/" + MESSAGE_ID))
                .andExpect(jsonPath("$.messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("IR-01: a refusal is problem+json with the machine-readable code, not a 202")
    void rendersARejectionAsProblemJson() throws Exception {
        // Arrange
        when(submitMessage.submit(any()))
                .thenReturn(SubmitMessageResult.rejected(
                        MESSAGE_ID, RejectionReason.QUOTA_EXCEEDED, "daily quota of ibank-retail is spent"));

        // Act + Assert
        mockMvc.perform(post(ApiV1.MESSAGES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.detail").value("daily quota of ibank-retail is spent"));
    }

    @Test
    @DisplayName("FR-1.5: a duplicate answers 409 and points at the original message")
    void rendersADuplicateAsConflict() throws Exception {
        // Arrange
        when(submitMessage.submit(any())).thenReturn(SubmitMessageResult.duplicate(MESSAGE_ID));

        // Act + Assert
        mockMvc.perform(post(ApiV1.MESSAGES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(jsonPath("$.messageId").value(MESSAGE_ID.toString()));
    }

    @Test
    @DisplayName("IR-01: a contract violation answers 400 naming the field, and never reaches the use case")
    void rejectsAMalformedDocument() throws Exception {
        // Arrange
        String noRecipient = """
                { "streamId": "ibank-retail", "externalMessageId": "x-1",
                  "content": { "sms": { "text": "hi" } } }
                """;

        // Act + Assert
        mockMvc.perform(post(ApiV1.MESSAGES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noRecipient))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("recipient"));
        verify(submitMessage, never()).submit(any(SubmitMessageCommand.class));
    }

    @Test
    @DisplayName("IR-02: a stream over its rate gets 429 with Retry-After")
    void appliesTheStreamRateLimit() throws Exception {
        // Arrange
        mockMvc = mockMvcWith(limiterOf(new RateLimitProperties(true, 1.0, 1, Map.of(), null)));
        when(submitMessage.submit(any())).thenReturn(SubmitMessageResult.accepted(MESSAGE_ID, MessageStatus.QUEUED));
        mockMvc.perform(post(ApiV1.MESSAGES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted());

        // Act + Assert
        mockMvc.perform(post(ApiV1.MESSAGES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("§8.2: the status of a message is served by its identifier")
    void servesTheStatusById() throws Exception {
        // Arrange
        when(getMessage.get(any())).thenReturn(view());

        // Act + Assert
        mockMvc.perform(get(ApiV1.MESSAGES + "/" + MESSAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.delivery.channel").value("SMS"))
                .andExpect(jsonPath("$.delivery.provider").value("PLAYMOBILE"))
                .andExpect(jsonPath("$.history[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.history[1].status").value("DELIVERED"));
    }

    @Test
    @DisplayName("§8.2: the status is also served by (streamId, externalMessageId)")
    void servesTheStatusByExternalId() throws Exception {
        // Arrange
        when(getMessage.get(any())).thenReturn(view());

        // Act + Assert
        mockMvc.perform(get(ApiV1.MESSAGES)
                        .param("streamId", "ibank-retail")
                        .param("externalMessageId", "ibank-2026-000123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalMessageId").value("ibank-2026-000123"));
    }

    @Test
    @DisplayName("An unknown message answers 404 with problem+json")
    void answers404ForAnUnknownMessage() throws Exception {
        // Arrange
        when(getMessage.get(any())).thenThrow(NotFoundException.of("message", MESSAGE_ID));

        // Act + Assert
        mockMvc.perform(get(ApiV1.MESSAGES + "/" + MESSAGE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("A path that is not a UUID is a contract violation, not a 500")
    void rejectsAMalformedIdentifier() throws Exception {
        // Act + Assert
        mockMvc.perform(get(ApiV1.MESSAGES + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("messageId"));
    }

    private MockMvc mockMvcWith(StreamRateLimiter rateLimiter) {
        InboundMessageCodec codec = new InboundMessageCodec(new InboundJson(), new InboundPayloadMapperImpl());
        // Аутентификация в контуре теста выключена: SecurityProperties.disabled() означает
        // «никто не аутентифицирован», и StreamAccessGuard пропускает любой поток (SEC-01).
        AuthenticatedCaller caller = new AuthenticatedCaller(SecurityProperties.disabled());
        MessageController controller = new MessageController(
                codec,
                submitMessage,
                getMessage,
                rateLimiter,
                new StreamAccessGuard(caller),
                caller,
                new RestResponseMapperImpl());
        ProblemFactory problems = new ProblemFactory();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(
                        new SubmissionRejectionHandler(problems),
                        new ContractViolationHandler(problems),
                        new NotFoundHandler(problems),
                        new RateLimitHandler(problems))
                .build();
    }

    private static MessageView view() {
        Instant acceptedAt = Instant.parse("2026-08-08T09:00:00Z");
        return new MessageView(
                MESSAGE_ID,
                StreamId.of("ibank-retail"),
                ExternalMessageId.of("ibank-2026-000123"),
                null,
                MessageStatus.DELIVERED,
                null,
                new MessageView.Delivery(
                        Channel.SMS,
                        ProviderCode.of("PLAYMOBILE"),
                        1,
                        null,
                        acceptedAt,
                        acceptedAt.plusSeconds(4),
                        CorrelationId.of("8f14e45f"),
                        false),
                List.of(
                        new MessageView.Transition(MessageStatus.ACCEPTED, null, null, acceptedAt),
                        new MessageView.Transition(
                                MessageStatus.DELIVERED, null, "DELIVRD", acceptedAt.plusSeconds(4))));
    }

    private static StreamRateLimiter limiterOf(RateLimitProperties properties) {
        return new StreamRateLimiter(properties, StreamLimits.configurationOnly(properties));
    }
}
