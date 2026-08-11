package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.application.exception.ConfigurationConflictException;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.SuppressionMapper;
import uz.hamkorbank.commhub.application.port.in.ManageSuppressions;
import uz.hamkorbank.commhub.application.port.in.command.ReleaseSuppressionCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressAddressCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressClientCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.application.service.support.ConfigAuditor;
import uz.hamkorbank.commhub.application.service.support.RecipientAddresses;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.SuppressionEntryId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Administration of the suppression list (FR-5.1, FR-7.1).
 *
 * <p>What this service owns is what the aggregate cannot see: who the actor is, when "now" is, the hashing of
 * the address the operator typed (DB-04), and that every change leaves an audit entry. Adding somebody to
 * this list stops the Bank from reaching them, and taking them off it starts messages again — both are
 * answerable-for actions (FR-7.3, SEC-08).
 *
 * <p>Listing the same target twice is refused rather than merged. The two entries would differ in their
 * reason and their validity, and the one that ends up applying would be whichever the index happened to
 * return; an operator who meant to change a ban should see the one that exists and release it.
 */
@Service
public class SuppressionService implements ManageSuppressions {

    private static final String ENTITY = "suppression";

    private final SuppressionRepository suppressions;
    private final ClockPort clock;
    private final SuppressionMapper mapper;
    private final ConfigAuditor auditor;

    public SuppressionService(
            SuppressionRepository suppressions, ClockPort clock, SuppressionMapper mapper, ConfigAuditor auditor) {
        this.suppressions = Guard.notNull(suppressions, "suppressions");
        this.clock = Guard.notNull(clock, "clock");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.auditor = Guard.notNull(auditor, "auditor");
    }

    @Override
    @Transactional
    public SuppressionView suppressAddress(SuppressAddressCommand command) {
        Guard.notNull(command, "command");
        AddressHash addressHash = RecipientAddresses.parse(command.channel(), command.address());
        suppressions.findByAddress(addressHash, command.channel()).ifPresent(existing -> {
            throw conflict("address", existing);
        });
        SuppressionEntry entry = SuppressionEntry.forAddress(
                SuppressionEntryId.newId(),
                command.channel(),
                addressHash,
                command.reason(),
                clock.now(),
                identity(command.actor()));
        command.validUntilOptional().ifPresent(entry::expireAt);
        suppressions.save(entry);
        auditor.record(
                command.actor(), "suppression.add", ENTITY, entry.id().value().toString(), null, describe(entry));
        return mapper.toView(entry);
    }

    @Override
    @Transactional
    public SuppressionView suppressClient(SuppressClientCommand command) {
        Guard.notNull(command, "command");
        suppressions.findByClient(command.clientId(), command.channel()).ifPresent(existing -> {
            throw conflict("client", existing);
        });
        SuppressionEntry entry = SuppressionEntry.forClient(
                SuppressionEntryId.newId(),
                command.channel(),
                command.clientId(),
                command.reason(),
                clock.now(),
                identity(command.actor()));
        command.validUntilOptional().ifPresent(entry::expireAt);
        suppressions.save(entry);
        auditor.record(
                command.actor(), "suppression.add", ENTITY, entry.id().value().toString(), null, describe(entry));
        return mapper.toView(entry);
    }

    @Override
    @Transactional
    public void release(ReleaseSuppressionCommand command) {
        Guard.notNull(command, "command");
        SuppressionEntry entry = suppressions
                .findById(command.entryId())
                .orElseThrow(
                        () -> NotFoundException.of(ENTITY, command.entryId().value()));
        suppressions.delete(entry.id());
        auditor.record(
                command.actor(),
                "suppression.release",
                ENTITY,
                entry.id().value().toString(),
                describe(entry),
                null,
                command.reason());
    }

    /**
     * Name recorded as the author of the entry (FR-7.3).
     *
     * <p>A nameless actor — the Hub itself — does not add entries through this use case: the automatic path
     * (a provider blacklist report, an email hard bounce) goes through {@code SuppressionRegistrar}, which
     * records the provider that said so. An entry stamped "SYSTEM" would be the one nobody can explain later.
     */
    private static String identity(Actor actor) {
        if (actor.id() == null || actor.id().isBlank()) {
            throw new ConfigurationConflictException(
                    "editing the suppression list requires a named actor, %s has none (FR-7.3)"
                            .formatted(actor.type()));
        }
        return actor.id();
    }

    private static ConfigurationConflictException conflict(String target, SuppressionEntry existing) {
        return new ConfigurationConflictException(
                "this %s is already suppressed on %s with reason %s (entry %s); release that entry first"
                        .formatted(
                                target,
                                existing.channel().map(Channel::name).orElse("all channels"),
                                existing.reason(),
                                existing.id().value()));
    }

    private static String describe(SuppressionEntry entry) {
        return "channel=%s, target=%s, reason=%s, validUntil=%s"
                .formatted(
                        entry.channel().map(Channel::name).orElse("ALL"),
                        entry.addressHash()
                                .map(AddressHash::value)
                                .orElseGet(() -> "client:"
                                        + entry.clientId().orElseThrow().value()),
                        entry.reason(),
                        entry.validUntil().map(Instant::toString).orElse("never"));
    }
}
