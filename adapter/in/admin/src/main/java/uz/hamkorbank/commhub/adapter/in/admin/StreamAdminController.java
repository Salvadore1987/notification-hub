package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.StreamRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.StreamResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapper;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.admin.support.ProviderRefs;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.port.in.GetRoutingConfiguration;
import uz.hamkorbank.commhub.application.port.in.ManageStreams;
import uz.hamkorbank.commhub.application.port.in.ResumeStream;
import uz.hamkorbank.commhub.application.port.in.SuspendStream;
import uz.hamkorbank.commhub.application.port.in.command.StreamActionCommand;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Source streams (§11.2 "Входящие потоки", FR-1.3, ADMIN only).
 *
 * <p>The list is not paged. Streams are the Bank's own source systems — dozens, not thousands — and a
 * pager over a list that fits on one screen is a control that only ever hides something.
 *
 * <p>Suspending a stream is separate from updating it, because it is a different act with a different
 * consequence: an update changes what future submissions get, a suspension refuses them (FR-1.3). Both
 * are journalled; the suspension carries the reason header, which is what makes the entry worth reading
 * once somebody asks why a system stopped being accepted.
 */
@RestController
@RequestMapping(AdminApi.STREAMS)
public class StreamAdminController {

    private final GetRoutingConfiguration configuration;
    private final ManageStreams manageStreams;
    private final SuspendStream suspendStream;
    private final ResumeStream resumeStream;
    private final ProviderRefs providerRefs;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper viewMapper;
    private final AdminCommandMapper commandMapper;

    public StreamAdminController(
            GetRoutingConfiguration configuration,
            ManageStreams manageStreams,
            SuspendStream suspendStream,
            ResumeStream resumeStream,
            ProviderRefs providerRefs,
            AuthenticatedCaller caller,
            AdminViewMapper viewMapper,
            AdminCommandMapper commandMapper) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.manageStreams = Guard.notNull(manageStreams, "manageStreams");
        this.suspendStream = Guard.notNull(suspendStream, "suspendStream");
        this.resumeStream = Guard.notNull(resumeStream, "resumeStream");
        this.providerRefs = Guard.notNull(providerRefs, "providerRefs");
        this.caller = Guard.notNull(caller, "caller");
        this.viewMapper = Guard.notNull(viewMapper, "viewMapper");
        this.commandMapper = Guard.notNull(commandMapper, "commandMapper");
    }

    /** {@code GET /api/admin/v1/streams} — every registered stream (§11.2). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.STREAM_READER)
    public List<StreamResponse> list() {
        return configuration.streams().stream().map(viewMapper::toStream).toList();
    }

    /** {@code POST /api/admin/v1/streams/{streamId}} — registers a stream (FR-1.3). */
    @PostMapping(
            path = "/{streamId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public StreamResponse register(@PathVariable String streamId, @RequestBody StreamRequest request) {
        return viewMapper.toStream(manageStreams.register(
                commandMapper.toRegisterStream(streamId, request, defaultProvider(request), caller.actor())));
    }

    /** {@code PUT /api/admin/v1/streams/{streamId}} — edits defaults, quotas and limits (FR-1.3). */
    @PutMapping(
            path = "/{streamId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public StreamResponse update(@PathVariable String streamId, @RequestBody StreamRequest request) {
        return viewMapper.toStream(manageStreams.update(
                commandMapper.toUpdateStream(streamId, request, defaultProvider(request), caller.actor())));
    }

    /** {@code POST /api/admin/v1/streams/{streamId}/suspend} — stops accepting from it (FR-1.3). */
    @PostMapping(path = "/{streamId}/suspend", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public StreamResponse suspend(
            @PathVariable String streamId,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        suspendStream.suspend(action(streamId, reason));
        return find(streamId);
    }

    /** {@code POST /api/admin/v1/streams/{streamId}/resume} — starts accepting again (FR-1.3). */
    @PostMapping(path = "/{streamId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public StreamResponse resume(
            @PathVariable String streamId,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        resumeStream.resume(action(streamId, reason));
        return find(streamId);
    }

    private StreamActionCommand action(String streamId, String reason) {
        return new StreamActionCommand(
                AdminValues.parseRequired(streamId, "streamId", StreamId::of), caller.actor(), reason);
    }

    /**
     * The stream as it now is.
     *
     * <p>The action use cases answer a control result rather than the card, and the screen wants the
     * card: an operator who just suspended a stream is looking at what it now says, not at a
     * confirmation. Reading it back is one indexed lookup against a table with dozens of rows.
     */
    private StreamResponse find(String streamId) {
        return configuration.streams().stream()
                .filter(stream -> stream.streamId().value().equals(streamId))
                .findFirst()
                .map(viewMapper::toStream)
                .orElseThrow(
                        () -> uz.hamkorbank.commhub.application.exception.NotFoundException.of("stream", streamId));
    }

    private ProviderRef defaultProvider(StreamRequest request) {
        return request.defaults() == null
                ? null
                : providerRefs.resolve(request.defaults().provider(), "defaults.provider");
    }
}
