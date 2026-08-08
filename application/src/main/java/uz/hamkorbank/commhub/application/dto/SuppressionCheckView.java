package uz.hamkorbank.commhub.application.dto;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;

/**
 * Answer to "may this recipient be sent to?" (FR-5.1).
 *
 * @param entry the entry that blocks the send; {@code null} when nothing does
 */
public record SuppressionCheckView(Channel channel, boolean suppressed, SuppressionView entry) {

    public static SuppressionCheckView allowed(Channel channel) {
        return new SuppressionCheckView(channel, false, null);
    }

    public static SuppressionCheckView suppressed(Channel channel, SuppressionView entry) {
        return new SuppressionCheckView(channel, true, entry);
    }

    public Optional<SuppressionView> entryOptional() {
        return Optional.ofNullable(entry);
    }
}
