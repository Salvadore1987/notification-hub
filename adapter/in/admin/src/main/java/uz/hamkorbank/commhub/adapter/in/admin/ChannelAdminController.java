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
import uz.hamkorbank.commhub.adapter.in.admin.dto.ChannelRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ChannelResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapper;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.port.in.GetRoutingConfiguration;
import uz.hamkorbank.commhub.application.port.in.ManageChannels;
import uz.hamkorbank.commhub.application.port.in.command.ChannelStateCommand;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Channel profiles (§11.2 "Каналы и провайдеры", FR-2.2, FR-2.3, FR-2.7, ADMIN only).
 *
 * <p>There are exactly three channels and they are an enum, so there is no create and no delete here —
 * adding a channel is a release (AR-05), not a row somebody inserts. What an administrator edits is the
 * balancing strategy, the fallback order, the quiet-hours window and the quota, and what they switch is
 * the channel's state.
 *
 * <p>State is its own endpoint rather than a field of the configuration body, because disabling a
 * channel is an operational act on running traffic and an edit to its fallback order is not: the two
 * carry different consequences and want different audit entries.
 */
@RestController
@RequestMapping(AdminApi.CHANNELS)
public class ChannelAdminController {

    private final GetRoutingConfiguration configuration;
    private final ManageChannels manageChannels;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper viewMapper;
    private final AdminCommandMapper commandMapper;

    public ChannelAdminController(
            GetRoutingConfiguration configuration,
            ManageChannels manageChannels,
            AuthenticatedCaller caller,
            AdminViewMapper viewMapper,
            AdminCommandMapper commandMapper) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.manageChannels = Guard.notNull(manageChannels, "manageChannels");
        this.caller = Guard.notNull(caller, "caller");
        this.viewMapper = Guard.notNull(viewMapper, "viewMapper");
        this.commandMapper = Guard.notNull(commandMapper, "commandMapper");
    }

    /** {@code GET /api/admin/v1/channels} — all three channels with their state (§11.2). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public List<ChannelResponse> list() {
        return configuration.channels().stream().map(viewMapper::toChannel).toList();
    }

    /** {@code PUT /api/admin/v1/channels/{channel}} — strategy, fallback, quiet hours, quota (FR-2.2). */
    @PutMapping(
            path = "/{channel}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public ChannelResponse configure(@PathVariable String channel, @RequestBody ChannelRequest request) {
        return viewMapper.toChannel(
                manageChannels.configure(commandMapper.toConfigureChannel(channel, request, caller.actor())));
    }

    /** {@code POST /api/admin/v1/channels/{channel}/state/{status}} — enable, disable, maintenance (FR-2.7). */
    @PostMapping(path = "/{channel}/state/{status}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public ChannelResponse changeState(
            @PathVariable String channel,
            @PathVariable String status,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        return viewMapper.toChannel(manageChannels.changeState(new ChannelStateCommand(
                caller.actor(),
                AdminValues.requiredEnum(Channel.class, channel, "channel"),
                AdminValues.requiredEnum(ChannelStatus.class, status, "status"),
                reason)));
    }
}
