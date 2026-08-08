package uz.hamkorbank.commhub.domain.model.vo;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Identifier of the message on the provider side, stored on the delivery attempt (§9.1, PR-03).
 *
 * <p>For Playmobile the Hub generates it itself (≤ 20 characters, {@code <orgPrefix><number>}); for
 * SMS Gate it is the {@code id} returned by the API. Provider-specific length limits are enforced by
 * the adapters.
 */
public record ProviderMessageId(String value) {

    public static final int MAX_LENGTH = 64;

    public ProviderMessageId {
        Guard.notBlank(value, "ProviderMessageId.value");
        Guard.maxLength(value, MAX_LENGTH, "ProviderMessageId.value");
    }

    public static ProviderMessageId of(String value) {
        return new ProviderMessageId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
