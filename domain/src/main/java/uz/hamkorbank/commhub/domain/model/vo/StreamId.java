package uz.hamkorbank.commhub.domain.model.vo;

import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Identifier (code) of an inbound stream, i.e. of a registered source system — {@code mobile-app},
 * {@code crm}, {@code undiruv} (FR-1.3, §6.4, §18.4).
 */
public record StreamId(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,63}$");

    public StreamId {
        Guard.matches(value, PATTERN, "StreamId.value");
    }

    public static StreamId of(String value) {
        return new StreamId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
