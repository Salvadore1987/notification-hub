package uz.hamkorbank.commhub.application.mapper;

import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.domain.model.SuppressionEntry;

/**
 * Conversion of the suppression aggregate into its read model (FR-5.1).
 *
 * <p>A default method for the same reason as the other mappers here: the aggregate exposes {@code Optional}
 * accessors that the generator cannot introspect. The rule the mapper upholds is the one that matters — no
 * use case builds a view of its own.
 */
@Mapper(componentModel = "spring")
public interface SuppressionMapper {

    default SuppressionView toView(SuppressionEntry entry) {
        return new SuppressionView(
                entry.id(),
                entry.channel().orElse(null),
                entry.addressHash().orElse(null),
                entry.clientId().orElse(null),
                entry.reason(),
                entry.validUntil().orElse(null),
                entry.createdBy().orElse(null),
                entry.createdAt());
    }
}
