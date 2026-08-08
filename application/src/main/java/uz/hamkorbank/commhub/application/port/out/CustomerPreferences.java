package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Preferences recorded for one client (FR-8.2).
 *
 * @param preferredChannels channels in the order the client prefers them; empty means "no preference"
 * @param locale preferred content locale; {@code null} means "take the stream default"
 * @param marketingOptIn consent for non-transactional traffic (FR-5.2)
 * @param quietHours personal quiet-hours window; {@code null} means "take channel/stream setting"
 */
public record CustomerPreferences(
        ClientId clientId,
        List<Channel> preferredChannels,
        ContentLocale locale,
        boolean marketingOptIn,
        QuietHours quietHours) {

    public CustomerPreferences {
        Guard.notNull(clientId, "CustomerPreferences.clientId");
        preferredChannels = Guard.copyOf(preferredChannels);
    }

    public Optional<ContentLocale> localeOptional() {
        return Optional.ofNullable(locale);
    }

    public Optional<QuietHours> quietHoursOptional() {
        return Optional.ofNullable(quietHours);
    }
}
