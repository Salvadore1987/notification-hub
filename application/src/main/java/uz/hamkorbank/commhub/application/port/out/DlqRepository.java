package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.DlqEntry;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;

/** Dead-letter entries of messages that stayed undelivered (§10.1 {@code dlq_entry}, FR-3.3). */
public interface DlqRepository {

    DlqEntry save(DlqEntry entry);

    Optional<DlqEntry> findByMessageId(MessageId messageId);

    /** Entries an operator may still retry, oldest first (FR-3.3). */
    List<DlqEntry> findRetryable(int limit);
}
