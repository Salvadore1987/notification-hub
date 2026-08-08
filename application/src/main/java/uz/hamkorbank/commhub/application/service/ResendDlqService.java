package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ResendDlqResult;
import uz.hamkorbank.commhub.application.port.in.ResendDlq;
import uz.hamkorbank.commhub.application.port.in.command.ResendDlqCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Manual retry of DLQ entries, single or in bulk (FR-3.3).
 *
 * <p>The entry is marked as retried — it may be used once — and the message goes back to
 * {@code QUEUED}, from where the sending saga resumes with a new delivery attempt (ST-02). Entries
 * that are archived or already retried are reported as skipped instead of failing the whole request,
 * which is what a bulk retry over a filtered DLQ list needs.
 */
@Service
public class ResendDlqService implements ResendDlq {

    private static final String ENTITY_TYPE = "dlq-entry";

    private final ClockPort clock;
    private final DlqRepository dlqEntries;
    private final MessageRepository messages;
    private final MessageStatusNotifier notifier;
    private final AuditPort audit;

    public ResendDlqService(
            ClockPort clock,
            DlqRepository dlqEntries,
            MessageRepository messages,
            MessageStatusNotifier notifier,
            AuditPort audit) {
        this.clock = Guard.notNull(clock, "clock");
        this.dlqEntries = Guard.notNull(dlqEntries, "dlqEntries");
        this.messages = Guard.notNull(messages, "messages");
        this.notifier = Guard.notNull(notifier, "notifier");
        this.audit = Guard.notNull(audit, "audit");
    }

    @Override
    @Transactional
    public ResendDlqResult resend(ResendDlqCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        List<MessageId> requeued = new ArrayList<>();
        List<MessageId> skipped = new ArrayList<>();
        for (MessageId messageId : command.messageIds()) {
            if (retry(messageId, command.actor(), now)) {
                requeued.add(messageId);
            } else {
                skipped.add(messageId);
            }
        }
        return new ResendDlqResult(requeued, skipped);
    }

    private boolean retry(MessageId messageId, Actor actor, Instant now) {
        Optional<DlqEntry> entry = dlqEntries.findByMessageId(messageId);
        Optional<Message> message = messages.findById(messageId);
        if (entry.isEmpty() || message.isEmpty() || !entry.get().isRetryable()) {
            return false;
        }
        entry.get().retry(operatorOf(actor), now);
        StatusChange change = message.get().requeueFromDlq(actor, now);
        dlqEntries.save(entry.get());
        messages.save(message.get());
        notifier.publish(message.get(), change);
        audit.write(AuditEntry.of(actor, "dlq.retry", ENTITY_TYPE, messageId.toString(), now));
        return true;
    }

    private static String operatorOf(Actor actor) {
        return actor.id() == null ? actor.type().name() : actor.id();
    }
}
