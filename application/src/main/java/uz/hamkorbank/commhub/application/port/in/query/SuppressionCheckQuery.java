package uz.hamkorbank.commhub.application.port.in.query;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * "May this recipient be sent to on this channel?" (FR-5.1).
 *
 * <p>Takes the address in the clear and hashes it on the way in, exactly as the sending path does — the
 * stored hash cannot be searched any other way (DB-04). Both an address and a client id may be given: a
 * client-wide ban and an address-level ban are different rows, and support usually has both values in front
 * of them.
 */
public record SuppressionCheckQuery(Channel channel, String address, ClientId clientId) {

    public SuppressionCheckQuery {
        Guard.notNull(channel, "SuppressionCheckQuery.channel");
        Guard.isTrue(
                (address != null && !address.isBlank()) || clientId != null,
                "SuppressionCheckQuery requires an address or a clientId");
    }

    public static SuppressionCheckQuery ofAddress(Channel channel, String address) {
        return new SuppressionCheckQuery(channel, address, null);
    }

    public Optional<String> addressOptional() {
        return Optional.ofNullable(address).filter(value -> !value.isBlank());
    }

    public Optional<ClientId> clientIdOptional() {
        return Optional.ofNullable(clientId);
    }
}
