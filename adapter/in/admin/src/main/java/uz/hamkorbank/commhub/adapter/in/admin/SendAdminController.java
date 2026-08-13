package uz.hamkorbank.commhub.adapter.in.admin;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SendBatchResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SendEstimateResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SendRequest;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapper;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.admin.support.RecipientListCsvCodec;
import uz.hamkorbank.commhub.adapter.in.admin.support.SendLimits;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageAcceptedResponse;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapper;
import uz.hamkorbank.commhub.adapter.in.rest.problem.SubmissionRejectedException;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.OperatorBatchResult;
import uz.hamkorbank.commhub.application.dto.SendEstimateView;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.EstimateSend;
import uz.hamkorbank.commhub.application.port.in.SendOperatorMessage;
import uz.hamkorbank.commhub.application.port.in.command.OperatorBatchCommand;
import uz.hamkorbank.commhub.application.port.in.command.OperatorSendCommand;
import uz.hamkorbank.commhub.application.port.in.query.SendEstimateQuery;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Sending from the panel: one message, or a batch from an uploaded list (§11.2, ADR-0038).
 *
 * <p>Deliberately not a share of {@code /api/v1}: that contract authorises by the stream claim of a
 * machine token (SEC-01), knows no {@code X-Commhub-Reason}, and legitimately allows free content —
 * none of which fits an employee pressing a button. Inside, both paths call the very same
 * {@code SubmitMessage}/{@code SubmitBatch} the Bank's systems do.
 *
 * <p>The two sending endpoints require a justification and refuse without one; the two estimates do
 * not. An estimate changes nothing, and demanding an explanation for looking at a number only teaches
 * people to type "test".
 */
@RestController
@RequestMapping(AdminApi.SEND)
public class SendAdminController {

    private final SendOperatorMessage sendMessage;
    private final EstimateSend estimates;
    private final RecipientListCsvCodec csvCodec;
    private final AuthenticatedCaller caller;
    private final AdminCommandMapper commandMapper;
    private final AdminViewMapper viewMapper;
    private final RestResponseMapper acceptedMapper;
    private final SendLimits limits;

    public SendAdminController(
            SendOperatorMessage sendMessage,
            EstimateSend estimates,
            RecipientListCsvCodec csvCodec,
            AuthenticatedCaller caller,
            AdminCommandMapper commandMapper,
            AdminViewMapper viewMapper,
            RestResponseMapper acceptedMapper,
            SendLimits limits) {
        this.sendMessage = Guard.notNull(sendMessage, "sendMessage");
        this.estimates = Guard.notNull(estimates, "estimates");
        this.csvCodec = Guard.notNull(csvCodec, "csvCodec");
        this.caller = Guard.notNull(caller, "caller");
        this.commandMapper = Guard.notNull(commandMapper, "commandMapper");
        this.viewMapper = Guard.notNull(viewMapper, "viewMapper");
        this.acceptedMapper = Guard.notNull(acceptedMapper, "acceptedMapper");
        this.limits = Guard.notNull(limits, "limits");
    }

    /** {@code POST /api/admin/v1/send/estimate} — what one message would cost (FR-4.4). */
    @PostMapping(
            path = "/estimate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SENDER)
    public SendEstimateResponse estimate(@RequestBody SendRequest request) {
        SendEstimateView view = estimates.estimate(commandMapper.toSendEstimate(request));
        return viewMapper.toSendEstimate(view, List.of());
    }

    /** {@code POST /api/admin/v1/send/estimate} with a CSV body — what the whole list would cost. */
    @PostMapping(path = "/estimate", consumes = AdminApi.TEXT_CSV, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SENDER)
    public SendEstimateResponse estimateList(@RequestBody String body, Filters filters) {
        RecipientListCsvCodec.Parsed parsed = parse(body, filters.channelValue());
        SendEstimateQuery query = new SendEstimateQuery(
                filters.streamIdValue(),
                filters.templateCodeValue(),
                filters.localeValue(),
                filters.channelValue(),
                filters.trafficClassValue(),
                parsed.rows().stream()
                        .map(item -> new SendEstimateQuery.Row(item.recipient(), item.variables()))
                        .toList());
        return viewMapper.toSendEstimate(estimates.estimate(query), parsed.failures());
    }

    /**
     * {@code POST /api/admin/v1/send/message} — one message, by the published template (ADR-0038).
     *
     * <p>A refusal is a problem document, exactly as in §8.2 — never a 200 carrying a rejected status
     * inside (D-12). The operator is standing in front of the screen waiting to be told why the send
     * did not happen, and "quota exhausted" is a sentence they can act on; a 200 whose body they have
     * to read is one they will read as "sent".
     */
    @PostMapping(
            path = "/message",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SENDER)
    public MessageAcceptedResponse send(
            @RequestBody SendRequest request,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        OperatorSendCommand command = commandMapper.toOperatorSend(request, caller.actor(), justification(reason));
        SubmitMessageResult result = sendMessage.send(command);
        if (!result.isAccepted()) {
            throw new SubmissionRejectedException(result);
        }
        return acceptedMapper.toAccepted(result);
    }

    /** {@code POST /api/admin/v1/send/batch} — a batch from the uploaded list (FR-1.6). */
    @PostMapping(path = "/batch", consumes = AdminApi.TEXT_CSV, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.SENDER)
    public SendBatchResponse sendBatch(
            @RequestBody String body,
            Filters filters,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        RecipientListCsvCodec.Parsed parsed = parse(body, filters.channelValue());
        OperatorBatchResult result = sendMessage.sendBatch(new OperatorBatchCommand(
                caller.actor(), justification(reason), null, filters.templateRef(), filters.target(), parsed.rows()));
        return viewMapper.toSendBatch(result, parsed.failures());
    }

    private RecipientListCsvCodec.Parsed parse(String body, Channel channel) {
        RecipientListCsvCodec.Parsed parsed;
        try {
            parsed = csvCodec.parse(new StringReader(body), ',', channel);
        } catch (IOException e) {
            throw InboundContractException.invalid("file", "could not be read", e);
        }
        if (parsed.rows().size() > limits.maxRecipients()) {
            throw InboundContractException.invalid(
                    "file",
                    "has %d rows, more than the %d a single send may carry"
                            .formatted(parsed.rows().size(), limits.maxRecipients()));
        }
        return parsed;
    }

    /**
     * The justification of FR-7.3, required for anything that actually sends.
     *
     * <p>Refused when absent rather than defaulted: the panel asks the operator for it, and an empty
     * one arriving here means the screen sent the request without asking.
     */
    private static String justification(String reason) {
        if (reason == null || reason.isBlank()) {
            throw InboundContractException.invalid(AdminApi.REASON_HEADER, "a justification is required for a send");
        }
        return reason;
    }

    /**
     * Query parameters of the CSV endpoints: the header of the send, since the body is the list.
     *
     * <p>A record bound by name, so the two endpoints declare the same five parameters once.
     */
    public record Filters(String streamId, String templateCode, String locale, String channel, String trafficClass) {

        uz.hamkorbank.commhub.domain.model.vo.StreamId streamIdValue() {
            return AdminValues.parseRequired(streamId, "streamId", uz.hamkorbank.commhub.domain.model.vo.StreamId::of);
        }

        uz.hamkorbank.commhub.domain.model.vo.TemplateCode templateCodeValue() {
            return AdminValues.parseRequired(
                    templateCode, "templateCode", uz.hamkorbank.commhub.domain.model.vo.TemplateCode::of);
        }

        uz.hamkorbank.commhub.domain.model.type.ContentLocale localeValue() {
            return AdminValues.requiredEnum(
                    uz.hamkorbank.commhub.domain.model.type.ContentLocale.class, locale, "locale");
        }

        Channel channelValue() {
            return AdminValues.requiredEnum(Channel.class, channel, "channel");
        }

        uz.hamkorbank.commhub.domain.model.type.TrafficClass trafficClassValue() {
            return AdminValues.optionalEnum(
                    uz.hamkorbank.commhub.domain.model.type.TrafficClass.class, trafficClass, "trafficClass");
        }

        uz.hamkorbank.commhub.domain.model.TemplateRef templateRef() {
            return new uz.hamkorbank.commhub.domain.model.TemplateRef(
                    templateCodeValue(), localeValue(), java.util.Map.of());
        }

        OperatorSendCommand.Target target() {
            return new OperatorSendCommand.Target(streamIdValue(), channelValue(), trafficClassValue(), null);
        }
    }
}
