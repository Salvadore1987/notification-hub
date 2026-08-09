package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Builds the {@code messages:send} document of FCM HTTP v1 (§9.4.1, PU-03, PU-05).
 *
 * <p>The shape is fixed by Google: one message per request, addressed by {@code token}, with a
 * platform-independent {@code notification} and {@code data} block plus per-platform overrides. What
 * this class decides is what goes where, and two of those decisions are worth stating.
 *
 * <p><b>The deep link travels in {@code data}, not in {@code notification}.</b> A tap target is
 * something the Bank's application resolves; putting it in the notification block would hand it to the
 * system tray, which knows nothing about the Bank's screens.
 *
 * <p><b>The {@code apns} block is written whenever the token is an iOS one</b> — the PU-05 mode, where
 * FCM translates to APNs itself. Without it, an iOS device receives Google's defaults: normal priority
 * and no expiry, so an OTP arrives when the phone next feels like waking up. The block mirrors what the
 * direct APNs adapter would have sent, which is the point: switching the mode must not change what the
 * customer sees.
 */
@Component
public class FcmMessageCodec {

    /** Key the deep link is delivered under in the data payload (PU-11). */
    public static final String DEEP_LINK_FIELD = "deepLink";

    private static final String APNS_PRIORITY_IMMEDIATE = "10";
    private static final String APNS_PRIORITY_CONSIDERATE = "5";

    private final FcmJson json;

    public FcmMessageCodec(FcmJson json) {
        this.json = Guard.notNull(json, "json");
    }

    /**
     * Renders one submission.
     *
     * @param validateOnly FCM's dry run: the message is validated and delivered to nobody (PU-13)
     * @param now moment of the call; the TTL is relative for Android and absolute for APNs, and the
     *     translation between the two needs a clock the message itself does not carry
     */
    public String encode(PushSubmission submission, FcmProperties.Sending sending, boolean validateOnly, Instant now) {
        Guard.notNull(submission, "submission");
        Guard.notNull(sending, "sending");
        Guard.notNull(now, "now");
        ObjectNode root = json.object();
        if (validateOnly) {
            root.put("validate_only", true);
        }
        ObjectNode message = root.putObject("message");
        message.put("token", submission.token().value());
        writeNotification(message, submission.content());
        writeData(message, submission.content());
        writeAndroid(message, submission, sending);
        if (submission.token().platform() == PushPlatform.IOS) {
            writeApns(message, submission, now);
        }
        return json.write(root);
    }

    private static void writeNotification(ObjectNode message, PushContent content) {
        ObjectNode notification = message.putObject("notification");
        notification.put("title", content.title());
        notification.put("body", content.body());
        if (content.image() != null && !content.image().isBlank()) {
            notification.put("image", content.image());
        }
    }

    /** Business key-values plus the deep link; every value is a string, as HTTP v1 requires (PU-03). */
    private static void writeData(ObjectNode message, PushContent content) {
        if (content.data().isEmpty()
                && (content.deepLink() == null || content.deepLink().isBlank())) {
            return;
        }
        ObjectNode data = message.putObject("data");
        for (Map.Entry<String, String> entry : content.data().entrySet()) {
            data.put(entry.getKey(), entry.getValue());
        }
        if (content.deepLink() != null && !content.deepLink().isBlank()) {
            data.put(DEEP_LINK_FIELD, content.deepLink());
        }
    }

    /** {@code android.priority}, {@code android.ttl} and {@code android.collapse_key} (PU-03). */
    private static void writeAndroid(ObjectNode message, PushSubmission submission, FcmProperties.Sending sending) {
        ObjectNode android = message.putObject("android");
        android.put("priority", sending.priorityOf(submission.context().trafficClass()));
        Duration ttl = ttlOf(submission, sending);
        if (ttl != null) {
            android.put("ttl", ttl.getSeconds() + "s");
        }
        if (submission.collapseKey() != null) {
            android.put("collapse_key", submission.collapseKey());
        }
    }

    /**
     * The APNs override FCM forwards for an iOS token (PU-05, PU-06).
     *
     * <p>{@code apns-expiration} is an absolute epoch second, unlike the Android relative TTL — the two
     * platforms disagree, and the disagreement is exactly what this block exists to absorb.
     */
    private static void writeApns(ObjectNode message, PushSubmission submission, Instant now) {
        ObjectNode apns = message.putObject("apns");
        ObjectNode headers = apns.putObject("headers");
        headers.put("apns-priority", immediate(submission) ? APNS_PRIORITY_IMMEDIATE : APNS_PRIORITY_CONSIDERATE);
        headers.put("apns-push-type", "alert");
        submission
                .timing()
                .expiresAt(now)
                .ifPresent(expiry -> headers.put("apns-expiration", String.valueOf(expiry.getEpochSecond())));
        if (submission.collapseKey() != null) {
            headers.put("apns-collapse-id", submission.collapseKey());
        }
        ObjectNode payload = apns.putObject("payload");
        ObjectNode aps = payload.putObject("aps");
        ObjectNode alert = aps.putObject("alert");
        alert.put("title", submission.content().title());
        alert.put("body", submission.content().body());
        aps.put("sound", "default");
    }

    private static boolean immediate(PushSubmission submission) {
        return switch (submission.context().trafficClass()) {
            case CRITICAL_OTP, TRANSACTIONAL -> true;
            case NOTIFICATION -> false;
        };
    }

    /** TTL of the message, or the deployment default when it carries none (PU-03). */
    private static Duration ttlOf(PushSubmission submission, FcmProperties.Sending sending) {
        return submission.timing().ttlOptional().orElse(sending.defaultTtl());
    }
}
