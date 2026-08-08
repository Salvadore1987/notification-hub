package uz.hamkorbank.commhub.domain.model.vo;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Channel-independent addressing block of the message envelope (MP-01, SRS §5.2).
 *
 * <p>Any subset of addresses may be present; the router only considers channels the recipient can
 * actually be reached on. At least one address or a client id is required.
 */
public record Recipient(ClientId clientId, Msisdn msisdn, EmailAddress email, List<PushToken> pushTokens) {

    public Recipient {
        pushTokens = Guard.copyOf(pushTokens);
        Guard.isTrue(
                clientId != null || msisdn != null || email != null || !pushTokens.isEmpty(),
                "Recipient must carry at least one address or a clientId");
    }

    public static Recipient ofMsisdn(Msisdn msisdn) {
        return new Recipient(null, msisdn, null, List.of());
    }

    public static Recipient ofEmail(EmailAddress email) {
        return new Recipient(null, null, email, List.of());
    }

    public static Recipient ofPushTokens(List<PushToken> pushTokens) {
        return new Recipient(null, null, null, pushTokens);
    }

    /** Whether the recipient carries an address usable by the given channel. */
    public boolean hasAddressFor(Channel channel) {
        Guard.notNull(channel, "channel");
        return switch (channel) {
            case SMS -> msisdn != null;
            case EMAIL -> email != null;
            case PUSH -> !pushTokens.isEmpty();
        };
    }

    /** Channel addresses the recipient can be reached on, in the declared channel order. */
    public List<Channel> reachableChannels() {
        return Arrays.stream(Channel.values()).filter(this::hasAddressFor).toList();
    }

    public List<PushToken> pushTokensFor(PushPlatform platform) {
        Guard.notNull(platform, "platform");
        return pushTokens.stream().filter(token -> token.platform() == platform).toList();
    }

    public Optional<ClientId> clientIdOptional() {
        return Optional.ofNullable(clientId);
    }

    /** Fully masked description for logs and UI (DB-04, OBS-03). */
    public String masked() {
        StringBuilder description = new StringBuilder("Recipient[");
        if (clientId != null) {
            description.append("clientId=").append(clientId.value()).append(", ");
        }
        if (msisdn != null) {
            description.append("msisdn=").append(msisdn.masked()).append(", ");
        }
        if (email != null) {
            description.append("email=").append(email.masked()).append(", ");
        }
        description.append("pushTokens=").append(pushTokens.size()).append(']');
        return description.toString();
    }

    @Override
    public String toString() {
        return masked();
    }
}
