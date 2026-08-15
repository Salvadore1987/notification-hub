package uz.hamkorbank.commhub.application.service.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.port.out.PushDelivery;
import uz.hamkorbank.commhub.application.port.out.PushDeliveryLogPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Everything a push fan-out writes down once the devices have answered (PU-04, PU-08, PU-09).
 *
 * <p><b>Why it is a class of its own.</b> Since the sending saga was split around the provider call
 * (ADR-0039) that call runs in <em>no</em> transaction: {@code DispatchPreparation} commits the attempt,
 * the fan-out talks to the platform, and {@code DispatchSettlement} applies the answer to a freshly
 * loaded aggregate. The bookkeeping below writes to the database, and both of its writes demand a
 * transaction that exists — {@code push_delivery} and the outbox row of an invalidated token are
 * {@code MANDATORY} on purpose — so it needs a boundary of its own. Writing it inline in the fan-out is
 * what broke push entirely: every message failed with an {@code ADAPTER_ERROR} that named a missing
 * transaction, retried, failed over and ended in the DLQ, while not one device row was ever written.
 *
 * <p>One transaction for all of it, not one per device: a retired token, its outbound event and the row
 * that says the platform refused it are three halves of one statement. A device row committed without
 * the suppression would send the Hub back to the same dead token tomorrow.
 *
 * <p>The transaction is deliberately opened <em>after</em> the platform calls, never around them: it
 * lasts for a couple of inserts instead of the fan-out's network latency, which on a bulk campaign is
 * the difference between a connection pool and a queue for one.
 */
@Component
public class PushDeliveryJournal {

    private final PushTokenRegistrar tokens;
    private final PushDeliveryLogPort deliveries;

    public PushDeliveryJournal(PushTokenRegistrar tokens, PushDeliveryLogPort deliveries) {
        this.tokens = Guard.notNull(tokens, "tokens");
        this.deliveries = Guard.notNull(deliveries, "deliveries");
    }

    /**
     * Retires the devices the platform refused and records what every device answered.
     *
     * @param attempt attempt of the saga this fan-out belongs to; every device row points at it (PU-09)
     */
    @Transactional
    public void record(Message message, ProviderRef provider, DeliveryAttempt attempt, List<TokenAck> answers) {
        Guard.notNull(message, "message");
        Guard.notNull(provider, "provider");
        Guard.notNull(attempt, "attempt");
        Guard.notNull(answers, "answers");
        List<PushDelivery> rows = new ArrayList<>(answers.size());
        for (TokenAck answer : answers) {
            boolean retired = answer.ack().invalidRecipient()
                    && tokens.invalidate(
                            message,
                            provider,
                            answer.token(),
                            answer.reason(),
                            answer.ack().respondedAt());
            rows.add(rowOf(message, provider, attempt, answer, retired));
        }
        deliveries.record(rows);
    }

    private static PushDelivery rowOf(
            Message message, ProviderRef provider, DeliveryAttempt attempt, TokenAck answer, boolean retired) {
        ProviderAck ack = answer.ack();
        return new PushDelivery(
                message.id(),
                attempt.id(),
                provider,
                RecipientAddresses.of(answer.token()),
                answer.token().platform(),
                ack.providerMessageId(),
                new PushDelivery.Outcome(
                        ack.result(), ack.responseCode(), ack.errorDescription(), retired, ack.respondedAt()));
    }
}
