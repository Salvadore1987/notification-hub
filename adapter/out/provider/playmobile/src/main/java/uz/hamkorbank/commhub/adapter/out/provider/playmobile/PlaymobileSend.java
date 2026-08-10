package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One message about to be written into a Playmobile {@code /send} document (§9.1).
 *
 * <p>Pairs the canonical submission with the {@code message-id} this adapter assigned to it. The id
 * cannot come from the submission unchanged: the Hub's generator produces twenty characters, and
 * Playmobile expects those twenty characters to already include the organisation prefix agreed with
 * them, so the adapter regenerates it with its own prefix (§9.1) and reports it back on the ack. That
 * is the id every delivery report will be matched by (PM-02).
 */
public record PlaymobileSend(SmsSubmission submission, ProviderMessageId providerMessageId) {

    public PlaymobileSend {
        Guard.notNull(submission, "PlaymobileSend.submission");
        Guard.notNull(providerMessageId, "PlaymobileSend.providerMessageId");
    }
}
