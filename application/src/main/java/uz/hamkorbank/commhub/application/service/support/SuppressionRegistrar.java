package uz.hamkorbank.commhub.application.service.support;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Puts an address on the suppression list because the delivery path said it is unusable (FR-5.1, EM-02,
 * §18.2, PU-04, PU-08).
 *
 * <p>Three sources feed it and they all mean the same thing — "stop sending here": a send response that
 * names the number as blacklisted (SMS Gate code 20), a delivery report that does (code 7), and an email
 * hard bounce (EM-02, arriving with the SMTP adapter in the Email stage). Each is a statement by the party
 * that would have to deliver the message, which is why the Hub records it without asking an operator.
 *
 * <p>Idempotent, because none of those sources is: providers repeat callbacks, the reconciliation of SG-03
 * asks again, and a bulk send hits the same dead address once per message. A repeat resolves to the entry
 * already in force, and only a genuinely new entry is counted (OBS-04) — an alert that fires once per
 * message of a batch says nothing.
 *
 * <p>The entry is written inside the caller's transaction, so a suppression that was recorded while its
 * status change rolled back cannot happen.
 */
@Component
public class SuppressionRegistrar {

    private final SuppressionRepository suppressions;
    private final MetricsPort metrics;

    public SuppressionRegistrar(SuppressionRepository suppressions, MetricsPort metrics) {
        this.suppressions = Guard.notNull(suppressions, "suppressions");
        this.metrics = Guard.notNull(metrics, "metrics");
    }

    /**
     * Suppresses the address this message was being delivered to.
     *
     * @param actor who reported it; the provider code ends up in {@code created_by} so that an operator
     *     looking at the entry can see which provider refused the address
     * @return the entry now in force, or empty when the channel of the message is unknown or the recipient
     *     carries no address for it — a client-id-only recipient has nothing to suppress
     */
    public Optional<SuppressionEntry> suppress(Message message, SuppressionReason reason, Actor actor, Instant now) {
        Guard.notNull(message, "message");
        Guard.notNull(reason, "reason");
        Optional<Channel> channel = channelOf(message);
        if (channel.isEmpty()) {
            return Optional.empty();
        }
        return suppress(message, channel.get(), reason, actor, now);
    }

    /** Same, for a caller that already knows the channel — the sending saga knows its provider. */
    public Optional<SuppressionEntry> suppress(
            Message message, Channel channel, SuppressionReason reason, Actor actor, Instant now) {
        Guard.notNull(message, "message");
        Guard.notNull(channel, "channel");
        Guard.notNull(reason, "reason");
        Guard.notNull(actor, "actor");
        Guard.notNull(now, "now");
        Optional<AddressHash> addressHash = RecipientAddresses.of(message.recipient(), channel);
        if (addressHash.isEmpty()) {
            return Optional.empty();
        }
        SuppressionEntry candidate = SuppressionEntry.forAddress(
                SuppressionEntryId.newId(), channel, addressHash.get(), reason, now, actor.toString());
        SuppressionEntry inForce = suppressions.saveIfAbsent(candidate);
        if (inForce.id().equals(candidate.id())) {
            metrics.recipientSuppressed(channel, reason);
        }
        return Optional.of(inForce);
    }

    /** Channel the message was actually sent on; the route is what the provider answered about. */
    private static Optional<Channel> channelOf(Message message) {
        return message.selectedChannel().or(() -> message.selectedProvider().map(ProviderRef::channel));
    }
}
