package uz.hamkorbank.commhub.domain.model.content;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Push payload: notification title/body, data payload, deep link and image (SRS §5.2, PU-03, PU-11).
 *
 * <p>Both APNs and FCM cap a push at 4 KB; {@link #exceedsPayloadLimit()} is checked during message
 * validation so that oversized messages never reach a provider (PU-11).
 */
public record PushContent(String title, String body, Map<String, String> data, String deepLink, String image)
        implements MessageContent {

    /** APNs and FCM payload limit (PU-11). */
    public static final int MAX_PAYLOAD_BYTES = 4096;

    public PushContent {
        Guard.notBlank(title, "PushContent.title");
        Guard.notBlank(body, "PushContent.body");
        data = Guard.copyOf(data);
    }

    public static PushContent of(String title, String body) {
        return new PushContent(title, body, Map.of(), null, null);
    }

    public static PushContent of(String title, String body, Map<String, String> data) {
        return new PushContent(title, body, data, null, null);
    }

    /** Returns a copy with the template-rendered title and body (FR-4.3). */
    public PushContent withRendered(String renderedTitle, String renderedBody) {
        return new PushContent(renderedTitle, renderedBody, data, deepLink, image);
    }

    /** Whether the payload breaches the 4 KB platform limit (PU-11). */
    public boolean exceedsPayloadLimit() {
        return payloadSizeBytes() > MAX_PAYLOAD_BYTES;
    }

    @Override
    public Channel channel() {
        return Channel.PUSH;
    }

    @Override
    public int payloadSizeBytes() {
        int size = utf8Length(title) + utf8Length(body) + utf8Length(deepLink) + utf8Length(image);
        for (Map.Entry<String, String> entry : data.entrySet()) {
            size += utf8Length(entry.getKey()) + utf8Length(entry.getValue());
        }
        return size;
    }

    private static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
