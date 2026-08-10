package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.BatchItemsResult;
import uz.hamkorbank.commhub.application.dto.OperatorBatchResult;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.SendOperatorMessage;
import uz.hamkorbank.commhub.application.port.in.SubmitBatch;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.application.port.in.command.CreateBatchCommand;
import uz.hamkorbank.commhub.application.port.in.command.OperatorBatchCommand;
import uz.hamkorbank.commhub.application.port.in.command.OperatorSendCommand;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.service.support.PublishedTemplates;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Sending from the admin panel (ADR-0038, §11.2).
 *
 * <p>Modelled on {@code TestSendService}, and different from it in the three ways that matter: the
 * content comes from a published template instead of a text field, the message is not marked as a test,
 * and no provider may be pinned — choosing one is the Hub's job (FR-2.2).
 *
 * <p>The audit entry is written <em>before</em> the send, as it is for a test send and for the same
 * reason: what has to be journalled is that a person aimed the Bank's infrastructure at live addresses,
 * and that is true whether or not the send then succeeded (FR-7.3).
 */
@Service
public class OperatorSendService implements SendOperatorMessage {

    /** Action verb of a panel send; deliberately distinct from {@code message.test-send}. */
    public static final String AUDIT_ACTION = "message.panel-send";

    private static final String AUDIT_ENTITY = "message";

    /** Items per chunk. Large enough to be few round trips, small enough to be a short transaction. */
    private static final int CHUNK_SIZE = 500;

    private final SubmitMessage submitMessage;
    private final SubmitBatch submitBatch;
    private final PublishedTemplates templates;
    private final AuditPort audit;
    private final ClockPort clock;

    public OperatorSendService(
            SubmitMessage submitMessage,
            SubmitBatch submitBatch,
            PublishedTemplates templates,
            AuditPort audit,
            ClockPort clock) {
        this.submitMessage = Guard.notNull(submitMessage, "submitMessage");
        this.submitBatch = Guard.notNull(submitBatch, "submitBatch");
        this.templates = Guard.notNull(templates, "templates");
        this.audit = Guard.notNull(audit, "audit");
        this.clock = Guard.notNull(clock, "clock");
    }

    @Override
    @Transactional
    public SubmitMessageResult send(OperatorSendCommand command) {
        Guard.notNull(command, "command");
        OperatorSendCommand.Target target = command.target();
        // Резолвится тем же компонентом, что и в смете: посчитали одну версию — отправили её же.
        templates.require(
                command.template().code(), target.channel(), command.template().locale());
        journal(command.actor(), command.externalMessageId().value(), command.reason());
        return submitMessage.submit(new SubmitMessageCommand(
                target.streamId(),
                command.externalMessageId(),
                null,
                command.recipient(),
                null,
                ChannelPlan.explicitChannel(target.channel()),
                command.template(),
                new SubmitMessageCommand.Delivery(
                        target.trafficClass(), null, target.timing(), null, null, false, null)));
    }

    /**
     * Creates the batch and uploads its rows in chunks.
     *
     * <p>Deliberately <em>not</em> {@code @Transactional}: {@code SubmitBatch.addItems} opens its own
     * transaction per chunk, and a fifty-thousand-row batch in one transaction would hold a connection
     * for minutes and roll the whole file back over the last bad row. The audit entry commits on its own,
     * before any of it.
     */
    @Override
    public OperatorBatchResult sendBatch(OperatorBatchCommand command) {
        Guard.notNull(command, "command");
        OperatorSendCommand.Target target = command.target();
        templates.require(
                command.template().code(), target.channel(), command.template().locale());
        BatchId batchId = command.batchId() == null ? BatchId.newId() : command.batchId();
        journal(command.actor(), batchId.toString(), command.reason());

        submitBatch.create(new CreateBatchCommand(
                batchId,
                target.streamId(),
                target.channel(),
                target.trafficClass(),
                target.timing(),
                command.template(),
                command.items().size(),
                false));

        long accepted = 0;
        long duplicates = 0;
        List<BatchItemsResult.ItemRejection> rejections = new ArrayList<>();
        for (int from = 0; from < command.items().size(); from += CHUNK_SIZE) {
            List<OperatorBatchCommand.Item> chunk = command.items()
                    .subList(from, Math.min(from + CHUNK_SIZE, command.items().size()));
            BatchItemsResult result = submitBatch.addItems(new AddBatchItemsCommand(
                    batchId,
                    target.streamId(),
                    chunk.stream().map(item -> itemOf(command, item)).toList()));
            accepted += result.accepted();
            duplicates += result.duplicates();
            rejections.addAll(result.rejections());
        }
        return new OperatorBatchResult(batchId, accepted, duplicates, rejections);
    }

    /** One row becomes an ordinary batch item whose template carries that row's own variables. */
    private static AddBatchItemsCommand.Item itemOf(OperatorBatchCommand command, OperatorBatchCommand.Item item) {
        TemplateRef template =
                new TemplateRef(command.template().code(), command.template().locale(), item.variables());
        return new AddBatchItemsCommand.Item(item.externalMessageId(), item.recipient(), null, template, null);
    }

    private void journal(uz.hamkorbank.commhub.domain.model.Actor actor, String entityId, String reason) {
        Instant now = clock.now();
        audit.write(
                AuditEntry.of(actor, AUDIT_ACTION, AUDIT_ENTITY, entityId, now).withReason(reason));
    }
}
