package uz.hamkorbank.commhub.application.mapper;

import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.DlqEntryView;
import uz.hamkorbank.commhub.domain.model.DlqEntry;

/**
 * DLQ entry → its read model (FR-3.3, §11.2 "DLQ").
 *
 * <p>A default method because {@code retryable} is a question only the aggregate answers — an entry is
 * retryable when it is neither archived nor already retried, and re-deriving that rule on the screen is
 * how a button appears on a row that will refuse it.
 */
@Mapper(componentModel = "spring")
public interface DlqMapper {

    default DlqEntryView toView(DlqEntry entry) {
        return new DlqEntryView(
                entry.messageId(),
                entry.reason(),
                entry.lastError().orElse(null),
                entry.movedAt(),
                entry.retriedBy().orElse(null),
                entry.retriedAt().orElse(null),
                entry.isArchived(),
                entry.isRetryable());
    }
}
