package uz.hamkorbank.commhub.application.mapper;

import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.AuditEntryView;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;

/**
 * Audit entry → its read model (FR-7.3).
 *
 * <p>A default method because the actor is a value object holding two fields the view flattens into one
 * name: what the journal shows is who acted, and "SYSTEM" is a who.
 */
@Mapper(componentModel = "spring")
public interface AuditMapper {

    default AuditEntryView toView(AuditEntry entry) {
        return new AuditEntryView(
                entry.occurredAt(),
                entry.actor().id() == null
                        ? entry.actor().type().name()
                        : entry.actor().id(),
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                entry.before(),
                entry.after(),
                entry.sourceIp());
    }
}
