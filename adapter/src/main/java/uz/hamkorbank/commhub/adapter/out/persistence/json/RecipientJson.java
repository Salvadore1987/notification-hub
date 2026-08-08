package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;

/**
 * {@link Recipient} inside the {@code message.recipient} column.
 *
 * <p>Addresses are stored unmasked — delivery needs them — which is what makes this column the PII of
 * the row (DB-04). Masking happens on the way out, in logs and in the admin panel, never here.
 */
public record RecipientJson(String clientId, String msisdn, String email, List<PushTokenJson> pushTokens) {

    public static RecipientJson of(Recipient recipient) {
        return new RecipientJson(
                recipient.clientId() == null ? null : recipient.clientId().value(),
                recipient.msisdn() == null ? null : recipient.msisdn().value(),
                recipient.email() == null ? null : recipient.email().value(),
                recipient.pushTokens().stream().map(PushTokenJson::of).toList());
    }

    public Recipient toDomain() {
        return new Recipient(
                clientId == null ? null : ClientId.of(clientId),
                msisdn == null ? null : Msisdn.of(msisdn),
                email == null ? null : EmailAddress.of(email),
                pushTokens == null
                        ? List.of()
                        : pushTokens.stream().map(PushTokenJson::toDomain).toList());
    }
}
