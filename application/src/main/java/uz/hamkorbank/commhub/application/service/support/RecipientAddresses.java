package uz.hamkorbank.commhub.application.service.support;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The address a message travels to on a channel, reduced to its hash (DB-04, FR-5.1, FR-5.4).
 *
 * <p>One place for it because three parts of the Hub have to agree on the answer: the delivery filters look
 * the hash up in the suppression list, the frequency counters count against it, and the administration
 * hashes what an operator typed in order to find the same row. A second implementation would eventually
 * hash the same number differently, and the failure would be silent — a ban that matches nothing.
 *
 * <p>Normalisation belongs to the value objects: {@code Msisdn} fixes {@code 9989xxxxxxxx} and
 * {@code EmailAddress} the case, so hashing goes through them. A push token has nothing to normalise — it is
 * an opaque string from the platform — and is hashed as it is.
 */
public final class RecipientAddresses {

    private RecipientAddresses() {}

    /** Hash of the address this recipient is reached at on the channel, when there is one. */
    public static Optional<AddressHash> of(Recipient recipient, Channel channel) {
        Guard.notNull(recipient, "recipient");
        Guard.notNull(channel, "channel");
        return switch (channel) {
            case SMS -> Optional.ofNullable(recipient.msisdn()).map(AddressHash::ofMsisdn);
            case EMAIL -> Optional.ofNullable(recipient.email()).map(AddressHash::ofEmail);
            case PUSH -> recipient.pushTokens().stream().findFirst().map(AddressHash::ofPushToken);
        };
    }

    /**
     * Hash of an address given as text, validated by the value object of the channel.
     *
     * @throws uz.hamkorbank.commhub.domain.exception.DomainValidationException when the text is not an
     *     address of that channel — a mistyped number has to fail here, not become a hash that matches
     *     nothing for the rest of its life
     */
    public static AddressHash parse(Channel channel, String address) {
        Guard.notNull(channel, "channel");
        Guard.notBlank(address, "address");
        return switch (channel) {
            case SMS -> AddressHash.ofMsisdn(Msisdn.normalize(address));
            case EMAIL -> AddressHash.ofEmail(EmailAddress.of(address));
            case PUSH -> AddressHash.of(address.trim());
        };
    }
}
