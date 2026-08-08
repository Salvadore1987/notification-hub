package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.SystemParameterView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.SystemParameterMapper;
import uz.hamkorbank.commhub.application.port.in.ManageSystemParameters;
import uz.hamkorbank.commhub.application.port.in.command.SetSystemParameterCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SystemParameter;
import uz.hamkorbank.commhub.application.port.out.SystemParameterPort;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Operator-editable system parameters (§11.2 "Администрирование", NF-06, FR-7.3).
 *
 * <p>Every write carries its before and after into the journal, which is the whole reason this is a use
 * case and not a table the panel writes to: a parameter is a lever on running traffic, and the value it
 * had before somebody changed it is not recoverable from anywhere else.
 *
 * <p>Removing a parameter that is not there is a 404 rather than a silent success. The two look the same
 * from the outside and mean different things — the second screen an operator opens after clearing a
 * parameter is the one that shows what is left.
 */
@Service
public class SystemParameterService implements ManageSystemParameters {

    private static final String ENTITY_TYPE = "system-parameter";

    private final ClockPort clock;
    private final SystemParameterPort parameters;
    private final AuditPort audit;
    private final SystemParameterMapper mapper;

    public SystemParameterService(
            ClockPort clock, SystemParameterPort parameters, AuditPort audit, SystemParameterMapper mapper) {
        this.clock = Guard.notNull(clock, "clock");
        this.parameters = Guard.notNull(parameters, "parameters");
        this.audit = Guard.notNull(audit, "audit");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    @Override
    @Transactional(readOnly = true)
    public List<SystemParameterView> list() {
        return parameters.findAll().stream().map(mapper::toView).toList();
    }

    @Override
    @Transactional
    public SystemParameterView set(SetSystemParameterCommand command) {
        Guard.notNull(command, "command");
        Guard.notNull(command.value(), "SetSystemParameterCommand.value");
        Instant now = clock.now();
        Optional<SystemParameter> before = parameters.find(command.key());
        SystemParameter after = parameters.save(new SystemParameter(
                command.key(),
                command.value(),
                command.description() == null
                        ? before.map(SystemParameter::description).orElse(null)
                        : command.description(),
                now,
                actorId(command.actor())));
        audit.write(new AuditEntry(
                command.actor(),
                before.isPresent() ? "system-parameter.update" : "system-parameter.create",
                ENTITY_TYPE,
                command.key(),
                before.map(SystemParameter::value).orElse(null),
                after.value(),
                null,
                now));
        return mapper.toView(after);
    }

    @Override
    @Transactional
    public void remove(SetSystemParameterCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        SystemParameter before =
                parameters.find(command.key()).orElseThrow(() -> NotFoundException.of(ENTITY_TYPE, command.key()));
        parameters.delete(command.key());
        audit.write(new AuditEntry(
                command.actor(),
                "system-parameter.delete",
                ENTITY_TYPE,
                command.key(),
                before.value(),
                null,
                null,
                now));
    }

    private static String actorId(Actor actor) {
        return actor.id() == null ? actor.type().name() : actor.id();
    }
}
