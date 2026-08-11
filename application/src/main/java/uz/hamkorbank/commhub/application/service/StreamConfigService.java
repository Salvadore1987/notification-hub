package uz.hamkorbank.commhub.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.StreamView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.ConfigMapper;
import uz.hamkorbank.commhub.application.port.in.ManageStreams;
import uz.hamkorbank.commhub.application.port.in.command.RegisterStreamCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateStreamCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Registration and settings of the source systems (FR-1.3, FR-2.4, FR-2.6, FR-5.3, IR-02, TC-02).
 *
 * <p>The defaults edited here are what TC-02 resolves against: a submission that names no traffic class
 * gets the one of its stream, and one that names no channel gets the stream's channel. Editing them is
 * therefore a routing change, and it is audited as one.
 */
@Service
public class StreamConfigService implements ManageStreams {

    private static final String ENTITY = "stream";

    private final StreamRepository streams;
    private final ClockPort clock;
    private final ConfigMapper mapper;
    private final ConfigAuditor auditor;

    public StreamConfigService(StreamRepository streams, ClockPort clock, ConfigMapper mapper, ConfigAuditor auditor) {
        this.streams = Guard.notNull(streams, "streams");
        this.clock = Guard.notNull(clock, "clock");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.auditor = Guard.notNull(auditor, "auditor");
    }

    @Override
    @Transactional
    public StreamView register(RegisterStreamCommand command) {
        Guard.notNull(command, "command");
        streams.findById(command.streamId()).ifPresent(existing -> {
            throw new ConfigurationConflictException("stream %s is already registered"
                    .formatted(command.streamId().value()));
        });
        Stream stream =
                Stream.register(command.streamId(), command.name(), command.integrationType(), command.defaults());
        if (command.quota() != null) {
            stream.updateQuota(command.quota());
        }
        if (command.rateLimit() != null) {
            stream.updateRateLimit(command.rateLimit());
        }
        stream.updateQuietHours(command.quietHours());
        streams.save(stream);
        auditor.record(command.actor(), "stream.register", ENTITY, stream.id().value(), null, describe(stream));
        return mapper.toView(stream, clock.now());
    }

    @Override
    @Transactional
    public StreamView update(UpdateStreamCommand command) {
        Guard.notNull(command, "command");
        Stream stream = streams.findById(command.streamId())
                .orElseThrow(
                        () -> NotFoundException.of(ENTITY, command.streamId().value()));
        String before = describe(stream);
        if (command.defaults() != null) {
            stream.updateDefaults(command.defaults());
        }
        if (command.quota() != null) {
            stream.updateQuota(command.quota());
        }
        if (command.rateLimit() != null) {
            stream.updateRateLimit(command.rateLimit());
        }
        if (command.quietHours() != null || command.clearQuietHours()) {
            stream.updateQuietHours(command.quietHours());
        }
        streams.save(stream);
        auditor.record(command.actor(), "stream.update", ENTITY, stream.id().value(), before, describe(stream));
        return mapper.toView(stream, clock.now());
    }

    private static String describe(Stream stream) {
        return "status=%s, defaults=%s, quotaUnlimited=%s, rateLimit=%s"
                .formatted(stream.status(), stream.defaults(), stream.quota().isUnlimited(), stream.rateLimit());
    }
}
