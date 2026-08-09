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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.KillSwitchRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.KillSwitchResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SystemParameterRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SystemParameterResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.rest.security.AuthenticatedCaller;
import uz.hamkorbank.commhub.application.port.in.KillSwitch;
import uz.hamkorbank.commhub.application.port.in.ManageSystemParameters;
import uz.hamkorbank.commhub.application.port.in.command.KillSwitchCommand;
import uz.hamkorbank.commhub.application.port.in.command.SetSystemParameterCommand;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The administration section: kill switch and system parameters (§11.2, FR-3.2, SEC-03, ADMIN only).
 *
 * <p>Flipping the kill switch does not touch a single message. The sending saga reads its state on every
 * turn and holds back everything it is allowed to hold back, so releasing the switch resumes the flow
 * exactly where it stopped. {@code CRITICAL_OTP} keeps moving unless the operator says otherwise, which
 * is the difference between an emergency stop and silencing one-time passwords.
 *
 * <p>Activating it requires a reason and the use case refuses without one (FR-7.3). That is not
 * paperwork: the audit entry for "everything stopped at 03:14" is worth reading only if it says why.
 *
 * <p>Users and roles are deliberately absent, and §11.2 allows for it — "пользователи/роли (или маппинг
 * SSO-групп)". The Hub stores no users and no passwords (§10.1): identity comes from the corporate SSO
 * and the mapping from its groups to the roles of {@code app_role} is deployment configuration, applied
 * by {@code WebSecurityConfig} at the token. An endpoint that edited users here would be a second
 * identity store for the Bank to keep in step with the first.
 */
@RestController
@RequestMapping(AdminApi.ADMINISTRATION)
public class AdministrationController {

    private final KillSwitch killSwitch;
    private final ManageSystemParameters parameters;
    private final AuthenticatedCaller caller;
    private final AdminViewMapper mapper;

    public AdministrationController(
            KillSwitch killSwitch,
            ManageSystemParameters parameters,
            AuthenticatedCaller caller,
            AdminViewMapper mapper) {
        this.killSwitch = Guard.notNull(killSwitch, "killSwitch");
        this.parameters = Guard.notNull(parameters, "parameters");
        this.caller = Guard.notNull(caller, "caller");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** {@code GET /api/admin/v1/administration/kill-switch} — the current state (FR-3.2). */
    @GetMapping(path = "/kill-switch", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public KillSwitchResponse killSwitchState() {
        return mapper.toKillSwitch(killSwitch.state());
    }

    /** {@code POST /api/admin/v1/administration/kill-switch} — stops or resumes all sends (FR-3.2). */
    @PostMapping(
            path = "/kill-switch",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public KillSwitchResponse apply(@RequestBody KillSwitchRequest request) {
        if (request.activate() && (request.reason() == null || request.reason().isBlank())) {
            throw InboundContractException.missing("reason");
        }
        return mapper.toKillSwitch(killSwitch.apply(new KillSwitchCommand(
                request.activate(), request.includeCriticalOtp(), caller.actor(), request.reason())));
    }

    /** {@code GET /api/admin/v1/administration/parameters} — every system parameter (NF-06). */
    @GetMapping(path = "/parameters", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public List<SystemParameterResponse> list() {
        return parameters.list().stream().map(mapper::toSystemParameter).toList();
    }

    /** {@code PUT /api/admin/v1/administration/parameters/{key}} — writes one parameter (NF-06). */
    @PutMapping(
            path = "/parameters/{key}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ADMIN)
    public SystemParameterResponse set(@PathVariable String key, @RequestBody SystemParameterRequest request) {
        if (request == null || request.value() == null) {
            throw InboundContractException.missing("value");
        }
        return mapper.toSystemParameter(parameters.set(
                new SetSystemParameterCommand(key, request.value(), request.description(), caller.actor())));
    }

    /** {@code DELETE /api/admin/v1/administration/parameters/{key}} — back to the deployed default. */
    @DeleteMapping(path = "/parameters/{key}")
    @PreAuthorize(AdminAuthority.ADMIN)
    public ResponseEntity<Void> remove(@PathVariable String key) {
        parameters.remove(SetSystemParameterCommand.remove(key, caller.actor()));
        return ResponseEntity.noContent().build();
    }
}
