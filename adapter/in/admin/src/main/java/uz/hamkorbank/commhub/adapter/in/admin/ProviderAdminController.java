package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ProviderRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ProviderResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TestSendRequest;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapper;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageAcceptedResponse;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapper;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.port.in.GetRoutingConfiguration;
import uz.hamkorbank.commhub.application.port.in.ManageProviders;
import uz.hamkorbank.commhub.application.port.in.SendTestMessage;
import uz.hamkorbank.commhub.application.port.in.command.DeleteProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStateCommand;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Provider profiles and the test send (§11.2 "Каналы и провайдеры", FR-2.1, FR-7.4, ADMIN only).
 *
 * <p>Health is read rather than written. It is decided passively from the delivery attempts the Hub
 * already records (FR-6.3, PR-02), so the panel shows {@code state.health} and {@code state.selectable}
 * and offers no button to overrule them — an operator who wants a provider out of routing disables it,
 * which is a decision with a name attached, instead of asserting that a working provider is down.
 *
 * <p>The test send lives here because this is the screen it belongs to, and it goes through the ordinary
 * pipeline: what an operator is checking is whether a message would get out, and a special sending path
 * would answer a question nobody asked (FR-7.4).
 */
@RestController
@RequestMapping(AdminApi.PROVIDERS)
public class ProviderAdminController {

    private final GetRoutingConfiguration configuration;
    private final ManageProviders manageProviders;
    private final SendTestMessage sendTestMessage;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper viewMapper;
    private final AdminCommandMapper commandMapper;
    private final RestResponseMapper acceptedMapper;

    public ProviderAdminController(
            GetRoutingConfiguration configuration,
            ManageProviders manageProviders,
            SendTestMessage sendTestMessage,
            AuthenticatedCaller caller,
            AdminViewMapper viewMapper,
            AdminCommandMapper commandMapper,
            RestResponseMapper acceptedMapper) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.manageProviders = Guard.notNull(manageProviders, "manageProviders");
        this.sendTestMessage = Guard.notNull(sendTestMessage, "sendTestMessage");
        this.caller = Guard.notNull(caller, "caller");
        this.viewMapper = Guard.notNull(viewMapper, "viewMapper");
        this.commandMapper = Guard.notNull(commandMapper, "commandMapper");
        this.acceptedMapper = Guard.notNull(acceptedMapper, "acceptedMapper");
    }

    /** {@code GET /api/admin/v1/providers} — every provider with its live state (§11.2, FR-6.3). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.PROVIDER_READER)
    public List<ProviderResponse> list() {
        return configuration.providers().stream().map(viewMapper::toProvider).toList();
    }

    /** {@code POST /api/admin/v1/providers/{code}} — registers a provider profile (FR-2.1). */
    @PostMapping(
            path = "/{code}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public ProviderResponse register(@PathVariable String code, @RequestBody ProviderRequest request) {
        return viewMapper.toProvider(
                manageProviders.register(commandMapper.toRegisterProvider(code, request, caller.actor())));
    }

    /** {@code PUT /api/admin/v1/providers/{providerId}} — weight, tariff, limits, quota (FR-2.1, FR-2.5). */
    @PutMapping(
            path = "/{providerId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public ProviderResponse update(@PathVariable String providerId, @RequestBody ProviderRequest request) {
        return viewMapper.toProvider(
                manageProviders.update(commandMapper.toUpdateProvider(providerId, request, caller.actor())));
    }

    /** {@code POST /api/admin/v1/providers/{providerId}/state/{state}} — enable, disable, maintenance (FR-2.7). */
    @PostMapping(path = "/{providerId}/state/{state}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public ProviderResponse changeState(
            @PathVariable String providerId,
            @PathVariable String state,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        return viewMapper.toProvider(manageProviders.changeState(new ProviderStateCommand(
                caller.actor(),
                providerId(providerId),
                AdminValues.requiredEnum(ProviderStateCommand.ProviderState.class, state, "state"),
                reason)));
    }

    /**
     * {@code DELETE /api/admin/v1/providers/{providerId}} — removes a profile (FR-2.1).
     *
     * <p>Refused by the use case while anything still routes through it, which is the answer an
     * administrator needs: a provider deleted out from under a fallback order is a channel that stops
     * working at the next failover rather than at the moment of the edit.
     */
    @DeleteMapping(path = "/{providerId}")
    @PreAuthorize(AdminAuthority.ADMIN)
    public ResponseEntity<Void> delete(
            @PathVariable String providerId,
            @RequestHeader(name = AdminApi.REASON_HEADER, required = false) String reason) {
        manageProviders.delete(new DeleteProviderCommand(caller.actor(), providerId(providerId), reason));
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /api/admin/v1/providers/test-send} — a marked test through the real pipeline (FR-7.4). */
    @PostMapping(
            path = "/test-send",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public MessageAcceptedResponse testSend(@RequestBody TestSendRequest request) {
        return acceptedMapper.toAccepted(sendTestMessage.send(commandMapper.toTestSend(request, caller.actor())));
    }

    private static ProviderId providerId(String raw) {
        return ProviderId.of(AdminValues.requiredUuid(raw, "providerId"));
    }
}
