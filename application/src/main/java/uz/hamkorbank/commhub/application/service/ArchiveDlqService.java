package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ArchiveDlqResult;
import uz.hamkorbank.commhub.application.port.in.ArchiveDlq;
import uz.hamkorbank.commhub.application.port.in.command.ArchiveDlqCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DlqRepository;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Taking DLQ entries off the working list (FR-3.3, §11.2 "DLQ").
 *
 * <p>Archiving changes nothing about the message: it stayed undelivered, its status says so, and the row
 * remains. What it changes is the operator's list, which is the point — a queue nobody can clear is a
 * queue nobody reads. Each entry is journalled with the reason, because deciding that some thousands of
 * messages will never be resent is precisely the decision somebody asks about a quarter later (FR-7.3).
 *
 * <p>Entries that are already archived are reported as skipped rather than failing the request, the same
 * way {@link ResendDlqService} treats an entry that was already retried: both buttons act on a selection
 * from a filtered list, and a list that moved under the operator must not cost them the other rows.
 */
@Service
public class ArchiveDlqService implements ArchiveDlq {

    private static final String ENTITY_TYPE = "dlq-entry";
    private static final String ACTION = "dlq.archive";

    private final ClockPort clock;
    private final DlqRepository dlqEntries;
    private final AuditPort audit;

    public ArchiveDlqService(ClockPort clock, DlqRepository dlqEntries, AuditPort audit) {
        this.clock = Guard.notNull(clock, "clock");
        this.dlqEntries = Guard.notNull(dlqEntries, "dlqEntries");
        this.audit = Guard.notNull(audit, "audit");
    }

    @Override
    @Transactional
    public ArchiveDlqResult archive(ArchiveDlqCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        List<MessageId> archived = new ArrayList<>();
        List<MessageId> skipped = new ArrayList<>();
        for (MessageId messageId : command.messageIds()) {
            if (archive(messageId, command, now)) {
                archived.add(messageId);
            } else {
                skipped.add(messageId);
            }
        }
        return new ArchiveDlqResult(archived, skipped);
    }

    private boolean archive(MessageId messageId, ArchiveDlqCommand command, Instant now) {
        Optional<DlqEntry> entry = dlqEntries.findByMessageId(messageId);
        if (entry.isEmpty() || entry.get().isArchived()) {
            return false;
        }
        entry.get().archive();
        dlqEntries.save(entry.get());
        audit.write(new AuditEntry(
                command.actor(), ACTION, ENTITY_TYPE, messageId.toString(), null, command.reason(), null, now));
        return true;
    }
}
