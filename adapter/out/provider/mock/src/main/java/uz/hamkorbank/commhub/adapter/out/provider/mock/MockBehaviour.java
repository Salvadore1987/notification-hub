package uz.hamkorbank.commhub.adapter.out.provider.mock;

import java.time.Instant;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;

/**
 * What the fake provider does with an address, decided by the address's last two characters (ADR-0041).
 *
 * <p>A rule per suffix rather than a switch in configuration: the operator sees it in the number they
 * typed, and "why did this one fail" is answered by looking at the recipient instead of at a yaml file.
 */
public enum MockBehaviour {

    /** Accepted, and a delivery report follows a moment later. */
    DELIVERED,

    /** Accepted, and the report says the message did not reach the phone. */
    UNDELIVERED,

    /** No answer at all: the call throws, which is what retry and failover key off (PR-01). */
    NO_ANSWER,

    /** Provider-wide refusal — the account is locked. Opens the breaker immediately (§18.1 code 102). */
    BLOCKING,

    /** The address itself is unusable; the Hub suppresses it and never tries again (FR-5.1). */
    INVALID_ADDRESS;

    /** Reads the rule out of the address; anything unrecognised is an ordinary, deliverable address. */
    public static MockBehaviour of(String address) {
        if (address == null || address.length() < 2) {
            return DELIVERED;
        }
        return switch (address.substring(address.length() - 2)) {
            case "00" -> DELIVERED;
            case "01" -> UNDELIVERED;
            case "02" -> NO_ANSWER;
            case "03" -> BLOCKING;
            case "04" -> INVALID_ADDRESS;
            default -> DELIVERED;
        };
    }

    /** Whether a delivery report follows the acceptance, and which one (AD-06). */
    public MessageStatus reportedStatus() {
        return this == UNDELIVERED ? MessageStatus.UNDELIVERED : MessageStatus.DELIVERED;
    }

    /**
     * The answer this rule gives to a submission.
     *
     * @throws MockProviderUnavailableException when the rule is {@link #NO_ANSWER} — the contract of
     *     phase 7 is that a provider which said nothing throws, and only that trips the breaker
     */
    public ProviderAck ackFor(ProviderMessageId providerMessageId, Instant now) {
        return switch (this) {
            case DELIVERED, UNDELIVERED -> ProviderAck.accepted(providerMessageId, "0", now);
            case NO_ANSWER -> throw new MockProviderUnavailableException("mock provider did not answer");
            case BLOCKING -> ProviderAck.failed("102", ErrorClass.BLOCKING, "mock provider account is locked", now);
            case INVALID_ADDRESS ->
                new ProviderAck(
                        AttemptResult.REJECTED,
                        null,
                        null,
                        "20",
                        ErrorClass.NON_RETRYABLE,
                        "mock provider refuses the address as unusable",
                        true,
                        now);
        };
    }
}
