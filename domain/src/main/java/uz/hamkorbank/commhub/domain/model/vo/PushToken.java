package uz.hamkorbank.commhub.domain.model.vo;

import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Device push token together with its platform (§9.4, PU-09).
 *
 * <p>The platform selects the provider adapter: FCM for Android/Web, APNs (or FCM, PU-05) for iOS.
 */
public record PushToken(String value, PushPlatform platform) {

    public static final int MAX_LENGTH = 4096;

    private static final int VISIBLE_SUFFIX_LENGTH = 6;

    public PushToken {
        Guard.notBlank(value, "PushToken.value");
        Guard.maxLength(value, MAX_LENGTH, "PushToken.value");
        Guard.notNull(platform, "PushToken.platform");
    }

    public static PushToken of(String value, PushPlatform platform) {
        return new PushToken(value, platform);
    }

    /** Masked form for logs and UI: {@code ANDROID:***abc123} (DB-04, OBS-03). */
    public String masked() {
        String suffix = value.length() <= VISIBLE_SUFFIX_LENGTH
                ? value
                : value.substring(value.length() - VISIBLE_SUFFIX_LENGTH);
        return platform + ":***" + suffix;
    }

    @Override
    public String toString() {
        return masked();
    }
}
