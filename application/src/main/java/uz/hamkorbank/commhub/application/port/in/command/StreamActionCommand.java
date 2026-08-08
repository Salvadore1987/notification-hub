package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Suspension or resumption of a whole inbound stream (FR-3.2, FR-1.3).
 *
 * <p>A suspended stream rejects new submissions with {@code STREAM_SUSPENDED} (IR-01) and its already
 * accepted messages stop being dispatched, except for {@code CRITICAL_OTP} (FR-3.2).
 */
public record StreamActionCommand(StreamId streamId, Actor actor, String reason) {

    public StreamActionCommand {
        Guard.notNull(streamId, "StreamActionCommand.streamId");
        Guard.notNull(actor, "StreamActionCommand.actor");
    }

    public static StreamActionCommand of(StreamId streamId, Actor actor) {
        return new StreamActionCommand(streamId, actor, null);
    }

    public Optional<String> reasonOptional() {
        return Optional.ofNullable(reason);
    }
}
