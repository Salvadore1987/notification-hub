package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.StreamControlResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.in.ResumeStream;
import uz.hamkorbank.commhub.application.port.in.SuspendStream;
import uz.hamkorbank.commhub.application.port.in.command.StreamActionCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Suspension and resumption of a whole inbound stream (FR-3.2, FR-1.3).
 *
 * <p>A suspended stream refuses new submissions with {@code STREAM_SUSPENDED} (IR-01); its already
 * accepted messages are deferred by the sending saga rather than cancelled, because the suspension is
 * reversible — and {@code CRITICAL_OTP} keeps flowing (FR-3.2).
 */
@Service
public class StreamControlService implements SuspendStream, ResumeStream {

    private static final String ENTITY_TYPE = "stream";

    private final ClockPort clock;
    private final StreamRepository streams;
    private final AuditPort audit;

    public StreamControlService(ClockPort clock, StreamRepository streams, AuditPort audit) {
        this.clock = Guard.notNull(clock, "clock");
        this.streams = Guard.notNull(streams, "streams");
        this.audit = Guard.notNull(audit, "audit");
    }

    @Override
    @Transactional
    public StreamControlResult suspend(StreamActionCommand command) {
        return apply(command, "stream.suspend", Stream::suspend);
    }

    @Override
    @Transactional
    public StreamControlResult resume(StreamActionCommand command) {
        return apply(command, "stream.resume", Stream::activate);
    }

    private StreamControlResult apply(StreamActionCommand command, String action, Consumer<Stream> transition) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        Stream stream = streams.findById(command.streamId())
                .orElseThrow(() -> NotFoundException.of(ENTITY_TYPE, command.streamId()));
        String before = stream.status().name();
        transition.accept(stream);
        streams.save(stream);
        audit.write(AuditEntry.changed(
                        command.actor(),
                        action,
                        ENTITY_TYPE,
                        stream.id().value(),
                        AuditEntry.Change.of(before, stream.status().name()),
                        now)
                .withReason(command.reason()));
        return new StreamControlResult(stream.id(), stream.status());
    }
}
