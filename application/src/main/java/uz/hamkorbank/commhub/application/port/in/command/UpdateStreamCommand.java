package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Change of the settings of a registered source system (FR-2.4, FR-2.6, FR-5.3, IR-02).
 *
 * <p>Patch semantics like {@link UpdateProviderCommand}: {@code null} keeps the current value. Quiet
 * hours are the exception — they are cleared by {@code clearQuietHours}, because "no window" and "do
 * not touch the window" are both meaningful and both would otherwise be {@code null}.
 */
public record UpdateStreamCommand(
        Actor actor,
        StreamId streamId,
        Stream.Defaults defaults,
        QuotaConfig quota,
        QuietHours quietHours,
        boolean clearQuietHours,
        RateLimit rateLimit) {

    public UpdateStreamCommand {
        Guard.notNull(actor, "UpdateStreamCommand.actor");
        Guard.notNull(streamId, "UpdateStreamCommand.streamId");
        Guard.isTrue(
                !clearQuietHours || quietHours == null, "UpdateStreamCommand cannot set and clear quiet hours at once");
    }

    public static UpdateStreamCommand ofDefaults(Actor actor, StreamId streamId, Stream.Defaults defaults) {
        return new UpdateStreamCommand(actor, streamId, defaults, null, null, false, null);
    }
}
