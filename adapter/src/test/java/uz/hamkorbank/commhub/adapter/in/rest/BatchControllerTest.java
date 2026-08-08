package uz.hamkorbank.commhub.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import uz.hamkorbank.commhub.adapter.in.BatchActions;
import uz.hamkorbank.commhub.adapter.in.contract.InboundBatchCodec;
import uz.hamkorbank.commhub.adapter.in.contract.InboundJson;
import uz.hamkorbank.commhub.adapter.in.contract.mapper.InboundPayloadMapperImpl;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.ContractViolationHandler;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.NotFoundHandler;
import uz.hamkorbank.commhub.adapter.in.rest.handlers.StateConflictHandler;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapperImpl;
import uz.hamkorbank.commhub.adapter.in.rest.problem.ProblemFactory;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.RateLimitProperties;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.StreamLimits;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.StreamRateLimiter;
import uz.hamkorbank.commhub.application.dto.BatchAcceptedResult;
import uz.hamkorbank.commhub.application.dto.BatchControlResult;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.dto.BatchProgressDto;
import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.port.in.GetBatch;
import uz.hamkorbank.commhub.application.port.in.PauseBatch;
import uz.hamkorbank.commhub.application.port.in.ResumeBatch;
import uz.hamkorbank.commhub.application.port.in.StartBatch;
import uz.hamkorbank.commhub.application.port.in.StopBatch;
import uz.hamkorbank.commhub.application.port.in.SubmitBatch;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.BatchActionCommand;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.type.ActorType;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** {@code /api/v1/batches} (§8.2, FR-1.6, FR-3.1, FR-3.2). */
@ExtendWith(MockitoExtension.class)
class BatchControllerTest {

    private static final BatchId BATCH_ID = BatchId.newId();

    private static final BatchProgressDto NO_PROGRESS = new BatchProgressDto(0, 0, 0, 0, 0, 0.0);

    @Mock
    private SubmitBatch submitBatch;

    @Mock
    private GetBatch getBatch;

    @Mock
    private StartBatch startBatch;

    @Mock
    private PauseBatch pauseBatch;

    @Mock
    private ResumeBatch resumeBatch;

    @Mock
    private StopBatch stopBatch;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        BatchController controller = new BatchController(
                new InboundBatchCodec(new InboundJson(), new InboundPayloadMapperImpl()),
                submitBatch,
                new BatchActions(startBatch, pauseBatch, resumeBatch, stopBatch),
                getBatch,
                limiterOf(RateLimitProperties.defaults()),
                new RestResponseMapperImpl());
        ProblemFactory problems = new ProblemFactory();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(
                        new ContractViolationHandler(problems),
                        new NotFoundHandler(problems),
                        new StateConflictHandler(problems))
                .build();
    }

    @Test
    @DisplayName("§8.2: a batch header answers 202 with the identifier the items go to")
    void createsABatch() throws Exception {
        // Arrange
        when(submitBatch.create(any())).thenReturn(new BatchAcceptedResult(BATCH_ID, BatchStatus.ACCEPTED, 25_000L));

        // Act + Assert
        mockMvc.perform(post(ApiV1.BATCHES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"streamId\": \"ibank-retail\", \"channel\": \"SMS\", \"expectedTotal\": 25000 }"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.batchId").value(BATCH_ID.toString()))
                .andExpect(jsonPath("$.total").value(25_000));
    }

    @Test
    @DisplayName("FR-1.4: a chunk with a bad item still delivers the good ones and lists the refusal")
    void reportsPerItemRejections() throws Exception {
        // Arrange
        when(submitBatch.addItems(any()))
                .thenReturn(new BatchItemsResult(
                        BATCH_ID,
                        1,
                        0,
                        List.of(new BatchItemsResult.ItemRejection(
                                ExternalMessageId.of("b-2"), RejectionReason.SUPPRESSED, "on the suppression list")),
                        new BatchProgressDto(2, 1, 0, 0, 0, 50.0)));

        // Act + Assert
        mockMvc.perform(post(ApiV1.BATCHES + "/" + BATCH_ID + "/items")
                        .param("streamId", "ibank-retail")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [
                                  { "externalMessageId": "b-1", "recipient": { "msisdn": "998901234567" },
                                    "content": { "sms": { "text": "hi" } } },
                                  { "externalMessageId": "b-2", "recipient": { "msisdn": "998901234568" },
                                    "content": { "sms": { "text": "hi" } } }
                                ] }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.rejections[0].externalMessageId").value("b-2"))
                .andExpect(jsonPath("$.rejections[0].reason").value("SUPPRESSED"))
                .andExpect(jsonPath("$.progress.completionPercent").value(50.0));

        ArgumentCaptor<AddBatchItemsCommand> command = ArgumentCaptor.forClass(AddBatchItemsCommand.class);
        verify(submitBatch).addItems(command.capture());
        assertThat(command.getValue().batchId()).isEqualTo(BATCH_ID);
        assertThat(command.getValue().streamId()).isEqualTo(StreamId.of("ibank-retail"));
    }

    @Test
    @DisplayName("FR-3.2: each action reaches its own use case and records who asked")
    void routesEachActionToItsUseCase() throws Exception {
        // Arrange
        when(pauseBatch.pause(any())).thenReturn(new BatchControlResult(BATCH_ID, BatchStatus.PAUSED, NO_PROGRESS));

        // Act
        mockMvc.perform(post(ApiV1.BATCHES + "/" + BATCH_ID + "/actions/pause")
                        .header(ApiV1.ACTOR_HEADER, "operator-42")
                        .header(ApiV1.REASON_HEADER, "customer complaint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // Assert
        ArgumentCaptor<BatchActionCommand> command = ArgumentCaptor.forClass(BatchActionCommand.class);
        verify(pauseBatch).pause(command.capture());
        assertThat(command.getValue().actor().type()).isEqualTo(ActorType.OPERATOR);
        assertThat(command.getValue().actor().id()).isEqualTo("operator-42");
        assertThat(command.getValue().reason()).isEqualTo("customer complaint");
    }

    @Test
    @DisplayName("An action nobody defined is a contract violation, not a 404")
    void refusesAnUnknownAction() throws Exception {
        // Act + Assert
        mockMvc.perform(post(ApiV1.BATCHES + "/" + BATCH_ID + "/actions/detonate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("action"));
    }

    @Test
    @DisplayName("FR-3.2: pausing an already-stopped batch answers 409, not 400")
    void answers409ForARefusedTransition() throws Exception {
        // Arrange
        when(pauseBatch.pause(any()))
                .thenThrow(InvalidStatusTransitionException.of("Batch", BatchStatus.STOPPED, BatchStatus.PAUSED));

        // Act + Assert
        mockMvc.perform(post(ApiV1.BATCHES + "/" + BATCH_ID + "/actions/pause"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("§8.2: the progress of a batch is served as one row, not by walking its messages")
    void servesTheProgress() throws Exception {
        // Arrange
        when(getBatch.get(any()))
                .thenReturn(new BatchView(
                        BATCH_ID,
                        StreamId.of("ibank-retail"),
                        Channel.SMS,
                        BatchStatus.PROCESSING,
                        1_000L,
                        new BatchProgressDto(1_000, 400, 380, 350, 20, 40.0),
                        Instant.parse("2026-08-08T09:00:00Z"),
                        null));

        // Act + Assert
        mockMvc.perform(get(ApiV1.BATCHES + "/" + BATCH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.progress.delivered").value(350))
                .andExpect(jsonPath("$.progress.completionPercent").value(40.0))
                .andExpect(jsonPath("$.costEstimate").doesNotExist());
    }

    private static StreamRateLimiter limiterOf(RateLimitProperties properties) {
        return new StreamRateLimiter(properties, StreamLimits.configurationOnly(properties));
    }
}
