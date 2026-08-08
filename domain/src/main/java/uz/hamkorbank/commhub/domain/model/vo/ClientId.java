package uz.hamkorbank.commhub.domain.model.vo;

import uz.hamkorbank.commhub.domain.support.Guard;

/** Bank client identifier; lets suppression and preferences work per client, not per address (FR-5.1). */
public record ClientId(String value) {

    public static final int MAX_LENGTH = 64;

    public ClientId {
        Guard.notBlank(value, "ClientId.value");
        Guard.maxLength(value, MAX_LENGTH, "ClientId.value");
    }

    public static ClientId of(String value) {
        return new ClientId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
