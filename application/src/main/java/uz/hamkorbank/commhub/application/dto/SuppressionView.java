package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One suppression-list entry as the administration screens show it (FR-5.1, §11.2).
 *
 * <p>The address appears as its hash and nothing else, because that is all the Hub has (DB-04). An operator
 * who needs to know whether a particular number is listed asks
 * {@link uz.hamkorbank.commhub.application.port.in.GetSuppressions#check}; a listing is for "how many
 * addresses did the bounces of last week add", and no screen needs to page through the Bank's phone numbers.
 *
 * @param channel {@code null} when the entry covers every channel
 * @param validUntil {@code null} for a permanent entry
 */
public record SuppressionView(
        SuppressionEntryId entryId,
        Channel channel,
        AddressHash addressHash,
        ClientId clientId,
        SuppressionReason reason,
        Instant validUntil,
        String createdBy,
        Instant createdAt) {

    public SuppressionView {
        Guard.notNull(entryId, "SuppressionView.entryId");
        Guard.notNull(reason, "SuppressionView.reason");
        Guard.notNull(createdAt, "SuppressionView.createdAt");
    }
}
