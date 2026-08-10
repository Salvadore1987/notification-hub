package uz.hamkorbank.commhub.adapter.out.persistence.json;

import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;

/** A push token of a recipient inside {@code message.recipient} (PU-01). */
public record PushTokenJson(String value, String platform) {

    public static PushTokenJson of(PushToken token) {
        return new PushTokenJson(token.value(), token.platform().name());
    }

    public PushToken toDomain() {
        return PushToken.of(value, PushPlatform.valueOf(platform));
    }
}
