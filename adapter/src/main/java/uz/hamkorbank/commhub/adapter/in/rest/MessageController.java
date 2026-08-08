package uz.hamkorbank.commhub.adapter.in.rest;

import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.contract.InboundMessageCodec;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageAcceptedResponse;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageStatusResponse;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapper;
import uz.hamkorbank.commhub.adapter.in.rest.problem.SubmissionRejectedException;
import uz.hamkorbank.commhub.adapter.in.rest.ratelimit.StreamRateLimiter;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.GetMessage;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.in.query.MessageQuery;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Single messages of the source systems, including OTP (§8.2 {@code /messages}, FR-1.1, FR-1.7).
 *
 * <p>Nothing but translation happens here (AR-06): the body is the shared inbound contract IK-03, the
 * codec turns it into a command, the use case decides, and the result becomes either a 202 or a
 * {@code problem+json} carrying the reason of IR-01.
 *
 * <p><strong>The OTP path is deliberately short.</strong> The request is accepted on a virtual thread
 * (AR-07), parsed once, and answered from the same transaction that accepted the message — no queue in
 * front, no work deferred behind a pool that bulk traffic shares. That, together with the traffic-class
 * isolation of the Kafka ingress, is what keeps the accept latency of FR-1.7 out of reach of a bulk
 * send; the p99 itself is a load-test target, not something a controller can promise.
 *
 * <p>The body is taken as a string rather than bound to a record because IK-03 is flat and wider than
 * the eight components a record may carry here; the codec that reads it is shared with the Kafka
 * consumers, so both transports accept exactly the same document.
 */
@RestController
@RequestMapping(ApiV1.MESSAGES)
public class MessageController {

    private final InboundMessageCodec codec;
    private final SubmitMessage submitMessage;
    private final GetMessage getMessage;
    private final StreamRateLimiter rateLimiter;
    private final RestResponseMapper mapper;

    public MessageController(
            InboundMessageCodec codec,
            SubmitMessage submitMessage,
            GetMessage getMessage,
            StreamRateLimiter rateLimiter,
            RestResponseMapper mapper) {
        this.codec = Guard.notNull(codec, "codec");
        this.submitMessage = Guard.notNull(submitMessage, "submitMessage");
        this.getMessage = Guard.notNull(getMessage, "getMessage");
        this.rateLimiter = Guard.notNull(rateLimiter, "rateLimiter");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /**
     * {@code POST /api/v1/messages} — accepts one message (§8.2, body = IK-03).
     *
     * <p>202 with the identifier the caller polls afterwards; a refusal is a problem document, never a
     * 202 with a rejected status inside, so a client that only checks the status code cannot mistake a
     * refusal for an acceptance.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageAcceptedResponse> submit(@RequestBody String body) {
        SubmitMessageCommand command = codec.read(body);
        rateLimiter.check(command.streamId().value());
        SubmitMessageResult result = submitMessage.submit(command);
        if (!result.isAccepted()) {
            throw new SubmissionRejectedException(result);
        }
        MessageAcceptedResponse response = mapper.toAccepted(result);
        return ResponseEntity.accepted()
                .location(URI.create(ApiV1.MESSAGES + "/" + response.messageId()))
                .body(response);
    }

    /** {@code GET /api/v1/messages/{messageId}} — status of one message (§8.2). */
    @GetMapping(path = "/{messageId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageStatusResponse byId(@PathVariable String messageId) {
        return mapper.toStatus(getMessage.get(MessageQuery.byId(messageId(messageId))));
    }

    /**
     * {@code GET /api/v1/messages?streamId=&externalMessageId=} — status by the identifier of the
     * source system (§8.2).
     *
     * <p>Both parameters are required: the external identifier is unique inside its stream only (FR-1.5).
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public MessageStatusResponse byExternalId(@RequestParam String streamId, @RequestParam String externalMessageId) {
        MessageQuery query = MessageQuery.byExternalId(streamId(streamId), externalMessageId(externalMessageId));
        return mapper.toStatus(getMessage.get(query));
    }

    private static MessageId messageId(String raw) {
        try {
            return MessageId.fromString(raw);
        } catch (RuntimeException e) {
            throw InboundContractException.invalid("messageId", "is not a UUID", e);
        }
    }

    private static StreamId streamId(String raw) {
        try {
            return StreamId.of(raw);
        } catch (RuntimeException e) {
            throw InboundContractException.invalid("streamId", e.getMessage(), e);
        }
    }

    private static ExternalMessageId externalMessageId(String raw) {
        try {
            return ExternalMessageId.of(raw);
        } catch (RuntimeException e) {
            throw InboundContractException.invalid("externalMessageId", e.getMessage(), e);
        }
    }
}
