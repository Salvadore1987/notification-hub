package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Registration of a source system (FR-1.3, FR-2.4, TC-02, §18.4).
 *
 * @param defaults channel, provider, traffic class, priority and balancing strategy applied to
 *     submissions that do not name them (FR-2.4, FR-2.3, TC-02)
 * @param quota count and cost ceilings of the stream; {@code null} means unlimited (FR-2.6)
 * @param rateLimit request rate of the synchronous API for this stream; {@code null} keeps the
 *     deployment default (IR-02)
 */
public record RegisterStreamCommand(
        Actor actor,
        StreamId streamId,
        String name,
        IntegrationType integrationType,
        Stream.Defaults defaults,
        QuotaConfig quota,
        QuietHours quietHours,
        RateLimit rateLimit) {

    public RegisterStreamCommand {
        Guard.notNull(actor, "RegisterStreamCommand.actor");
        Guard.notNull(streamId, "RegisterStreamCommand.streamId");
        Guard.notBlank(name, "RegisterStreamCommand.name");
        Guard.notNull(integrationType, "RegisterStreamCommand.integrationType");
        defaults = defaults == null ? Stream.Defaults.none() : defaults;
    }
}
