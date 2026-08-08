package uz.hamkorbank.commhub.application.port.out.provider;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.type.Channel;

/**
 * SMS channel output port implemented by every SMS provider adapter (AR-04, PM-01, SG-01).
 *
 * <p>Implementations are blocking HTTP clients executed on virtual threads (AR-07); timeouts, retry
 * with backoff and the circuit breaker are configured per provider by the adapter (PR-01).
 */
public interface SmsProviderPort extends ProviderPort {

    @Override
    default Channel channel() {
        return Channel.SMS;
    }

    /** Hands one message to the provider (Playmobile {@code /send}, SMS Gate {@code /api/v2/send}). */
    ProviderAck submit(SmsSubmission submission);

    /**
     * Hands a chunk of messages to the provider in one call where the API supports it (Playmobile
     * batch {@code /send}, SMS Gate {@code /api/v2/send_msgs}).
     *
     * <p>The default falls back to one call per message, which keeps adapters without batch support
     * trivial; the answers stay positional, one per submission.
     */
    default List<ProviderAck> submitBatch(List<SmsSubmission> submissions) {
        return submissions.stream().map(this::submit).toList();
    }
}
