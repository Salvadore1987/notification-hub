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
import uz.hamkorbank.commhub.adapter.in.admin.dto.DeployedAdapterResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ProviderRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ProviderResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TestSendRequest;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminCommandMapper;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.rest.dto.MessageAcceptedResponse;
import uz.hamkorbank.commhub.adapter.in.rest.mapper.RestResponseMapper;
import uz.hamkorbank.commhub.adapter.in.rest.problem.SubmissionRejectedException;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.port.in.GetDeployedAdapters;
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
    private final GetDeployedAdapters deployedAdapters;
    private final ManageProviders manageProviders;
    private final SendTestMessage sendTestMessage;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper viewMapper;
    private final AdminCommandMapper commandMapper;
    private final RestResponseMapper acceptedMapper;

    public ProviderAdminController(
            GetRoutingConfiguration configuration,
            GetDeployedAdapters deployedAdapters,
            ManageProviders manageProviders,
            SendTestMessage sendTestMessage,
            AuthenticatedCaller caller,
            AdminViewMapper viewMapper,
            AdminCommandMapper commandMapper,
            RestResponseMapper acceptedMapper) {
        this.configuration = Guard.notNull(configuration, "configuration");
        this.deployedAdapters = Guard.notNull(deployedAdapters, "deployedAdapters");
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

    /**
     * {@code GET /api/admin/v1/providers/adapters} — adapter types deployed on this contour (AR-04).
     *
     * <p>What the registration form offers instead of asking an operator to type an opaque string.
     * The answer is read off the channel-port beans, so a type the panel offers is a type a routed
     * message will resolve; a profile naming anything else fails at the first send, not at the edit.
     *
     * <p>{@code ADMIN} rather than the wider audience of {@code GET /providers}: the only caller is the
     * form behind {@code POST}/{@code PUT}, and a reader who cannot register a provider has no use for
     * the list. Neither reason header nor audit entry — this is deployment metadata with no customer in
     * it, and SEC-08 journals employees reading customer data.
     */
    @GetMapping(path = "/adapters", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public List<DeployedAdapterResponse> adapters() {
        return deployedAdapters.adapters().stream()
                .map(viewMapper::toDeployedAdapter)
                .toList();
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

    /**
     * {@code POST /api/admin/v1/providers/test-send} — a marked test through the real pipeline (FR-7.4).
     *
     * <p>The refusal is rendered as a problem document rather than hidden in a 200 (D-12): the whole
     * point of a test send is to learn what the configuration does, and "REJECTED" without a reason
     * answers nothing.
     */
    @PostMapping(
            path = "/test-send",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public MessageAcceptedResponse testSend(@RequestBody TestSendRequest request) {
        SubmitMessageResult result = sendTestMessage.send(commandMapper.toTestSend(request, caller.actor()));
        if (!result.isAccepted()) {
            throw new SubmissionRejectedException(result);
        }
        return acceptedMapper.toAccepted(result);
    }

    private static ProviderId providerId(String raw) {
        return ProviderId.of(AdminValues.requiredUuid(raw, "providerId"));
    }
}
