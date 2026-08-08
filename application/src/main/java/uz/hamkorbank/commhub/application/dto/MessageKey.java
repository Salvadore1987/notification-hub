package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Identification block of an outbound status event (§6.4).
 *
 * @param batchId {@code null} for single messages
 */
public record MessageKey(
        StreamId streamId,
        BatchId batchId,
        MessageId messageId,
        ExternalMessageId externalMessageId,
        CorrelationId correlationId) {

    public MessageKey {
        Guard.notNull(streamId, "MessageKey.streamId");
        Guard.notNull(messageId, "MessageKey.messageId");
        Guard.notNull(externalMessageId, "MessageKey.externalMessageId");
        Guard.notNull(correlationId, "MessageKey.correlationId");
    }
}
