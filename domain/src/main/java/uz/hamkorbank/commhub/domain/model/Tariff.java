package uz.hamkorbank.commhub.domain.model;

import java.util.Currency;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Price list of a provider (FR-2.1, FR-6.2).
 *
 * <p>SMS is charged per segment (§18.3), email and push per message; both components may be combined.
 * At least one of them must be present and both must use the same currency.
 *
 * @param perMessage fixed price of one message; {@code null} when only segments are charged
 * @param perSegment price of one SMS segment; {@code null} for non-segmented channels
 */
public record Tariff(Money perMessage, Money perSegment) {

    public Tariff {
        Guard.isTrue(perMessage != null || perSegment != null, "Tariff requires perMessage, perSegment or both");
        if (perMessage != null && perSegment != null) {
            Guard.isTrue(
                    perMessage.currency().equals(perSegment.currency()),
                    "Tariff components must use the same currency");
        }
    }

    public static Tariff perMessage(Money price) {
        return new Tariff(price, null);
    }

    public static Tariff perSegment(Money price) {
        return new Tariff(null, price);
    }

    /** Expected cost of a message with the given number of SMS segments (MP-06, FR-6.2). */
    public Money costOf(int segments) {
        Guard.notNegative(segments, "segments");
        if (perSegment == null) {
            return perMessage;
        }
        Money segmentCost = perSegment.multipliedBy(Math.max(1, segments));
        return perMessage == null ? segmentCost : perMessage.plus(segmentCost);
    }

    /** Currency the provider bills in (FR-2.1). */
    public Currency currency() {
        return perMessage != null ? perMessage.currency() : perSegment.currency();
    }
}
