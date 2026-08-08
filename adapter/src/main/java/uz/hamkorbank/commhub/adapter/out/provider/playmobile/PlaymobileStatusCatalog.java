package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;

/**
 * Playmobile delivery-report vocabulary → the canonical status model (§18.1, ST-03, PM-02).
 *
 * <p>§18.1 states the mapping by outcome and leaves the exact set of {@code status} words to be fixed
 * during the integration, so the table below covers the spellings the SMS-Broker documentation uses and
 * the obvious variants of them. Anything else is reported as unknown rather than guessed at — see
 * {@link PlaymobileCallbackTranslator} for why an unknown word is dropped and not refused.
 *
 * <p>{@code REJECTED} deliberately does not appear. A delivery report arrives after the message has
 * already reached {@code SENT_TO_PROVIDER}, and the state machine does not allow a rejection from
 * there (ST-01, §6.3): rejection is a verdict of the Hub's own pipeline, and a message the provider
 * took and then refused is undelivered. Mapping it otherwise would produce reports the use case
 * silently ignores.
 */
public final class PlaymobileStatusCatalog {

    private static final Map<String, MessageStatus> STATUSES = Map.ofEntries(
            Map.entry("delivered", MessageStatus.DELIVERED),
            Map.entry("deliver", MessageStatus.DELIVERED),
            Map.entry("not_delivered", MessageStatus.UNDELIVERED),
            Map.entry("notdelivered", MessageStatus.UNDELIVERED),
            Map.entry("undeliverable", MessageStatus.UNDELIVERED),
            Map.entry("failed", MessageStatus.UNDELIVERED),
            Map.entry("rejected", MessageStatus.UNDELIVERED),
            Map.entry("deleted", MessageStatus.UNDELIVERED),
            Map.entry("expired", MessageStatus.EXPIRED),
            Map.entry("ttl_expired", MessageStatus.EXPIRED),
            // Handed to the operator: progress, not an outcome — the message stays in flight (§18.1).
            Map.entry("transmitted", MessageStatus.SENT_TO_PROVIDER),
            Map.entry("accepted", MessageStatus.SENT_TO_PROVIDER),
            Map.entry("enroute", MessageStatus.SENT_TO_PROVIDER),
            Map.entry("sent", MessageStatus.SENT_TO_PROVIDER));

    private PlaymobileStatusCatalog() {}

    /** Canonical status of a report, or empty when the word is not one §18.1 accounts for. */
    public static Optional<MessageStatus> canonical(String providerStatus) {
        if (providerStatus == null || providerStatus.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                STATUSES.get(providerStatus.trim().toLowerCase(Locale.ROOT).replace('-', '_')));
    }
}
