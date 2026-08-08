package uz.hamkorbank.commhub.application.dto;

import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Outcome of suspending or resuming an inbound stream (FR-3.2, FR-1.3). */
public record StreamControlResult(StreamId streamId, StreamStatus status) {

    public StreamControlResult {
        Guard.notNull(streamId, "StreamControlResult.streamId");
        Guard.notNull(status, "StreamControlResult.status");
    }
}
