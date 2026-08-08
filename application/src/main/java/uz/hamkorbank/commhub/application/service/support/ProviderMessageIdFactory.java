package uz.hamkorbank.commhub.application.service.support;

import java.util.Locale;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Generates the provider-side message id for providers that expect the sender to supply one (§9.1).
 *
 * <p>Playmobile requires {@code <organisation prefix><number>} of at most 20 characters, unique per
 * sending system; the id is stored on the {@code DeliveryAttempt} and is the key delivery reports are
 * matched by (PM-02). It is derived from the internal {@code MessageId}, so the mapping stays
 * reproducible without another sequence: 20 characters minus the prefix of the low 64 bits of the
 * UUIDv7 in base 36.
 */
@Component
public class ProviderMessageIdFactory {

    /** Playmobile limit for {@code message-id} (§9.1). */
    public static final int MAX_LENGTH = 20;

    private static final int RADIX = 36;

    /** Builds the provider id of a message for a provider using the given organisation prefix. */
    public ProviderMessageId create(String organisationPrefix, MessageId messageId) {
        Guard.notNull(messageId, "messageId");
        String prefix = organisationPrefix == null ? "" : organisationPrefix.toUpperCase(Locale.ROOT);
        Guard.isTrue(prefix.length() < MAX_LENGTH, "organisation prefix leaves no room for the message number");
        String number = Long.toUnsignedString(messageId.value().getLeastSignificantBits(), RADIX)
                .toUpperCase(Locale.ROOT);
        int room = MAX_LENGTH - prefix.length();
        String suffix = number.length() <= room ? number : number.substring(number.length() - room);
        return ProviderMessageId.of(prefix + suffix);
    }
}
