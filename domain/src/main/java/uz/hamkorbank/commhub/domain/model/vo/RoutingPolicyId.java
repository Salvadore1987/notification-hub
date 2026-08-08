package uz.hamkorbank.commhub.domain.model.vo;

import java.util.UUID;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Identifier of a declarative routing policy — UUIDv7 (§10.1 {@code routing_policy}, FR-8.9). */
public record RoutingPolicyId(UUID value) {

    public RoutingPolicyId {
        Guard.notNull(value, "RoutingPolicyId.value");
    }

    public static RoutingPolicyId newId() {
        return new RoutingPolicyId(UuidV7.generate());
    }

    public static RoutingPolicyId of(UUID value) {
        return new RoutingPolicyId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
