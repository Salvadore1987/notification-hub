package uz.hamkorbank.commhub.adapter.out.provider.apns;

import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.domain.model.content.PushContent;

/**
 * Builds the APNs notification payload and reads its answers (§9.4.2, PU-06, PU-08).
 *
 * <p>The payload is Apple's: an {@code aps} dictionary the operating system interprets — the alert the
 * customer sees, the sound, the badge — plus any number of custom keys the application reads. The Hub's
 * business data goes into those custom keys, alongside {@code aps} and never inside it: a key Apple
 * does not know inside {@code aps} is a notification that silently fails to display.
 *
 * <p>The deep link is one such custom key, matching the field FCM delivers it under, so the Bank's iOS
 * application reads the same name whichever provider carried the notification (PU-05).
 */
@Component
public class ApnsMessageCodec {

    /** Key the deep link is delivered under, the same as in the FCM data payload. */
    public static final String DEEP_LINK_FIELD = "deepLink";

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Renders the notification for {@code POST /3/device/{token}} (PU-06). */
    public String encode(PushSubmission submission) {
        PushContent content = submission.content();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode aps = root.putObject("aps");
        ObjectNode alert = aps.putObject("alert");
        alert.put("title", content.title());
        alert.put("body", content.body());
        aps.put("sound", "default");
        for (Map.Entry<String, String> entry : content.data().entrySet()) {
            root.put(entry.getKey(), entry.getValue());
        }
        if (content.deepLink() != null && !content.deepLink().isBlank()) {
            root.put(DEEP_LINK_FIELD, content.deepLink());
        }
        if (content.image() != null && !content.image().isBlank()) {
            // mutable-content разрешает расширению приложения подтянуть картинку до показа;
            // без него URL останется полем, которое некому прочитать.
            aps.put("mutable-content", 1);
            root.put("image", content.image());
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize an APNs payload (§9.4.2)", e);
        }
    }

    /**
     * The {@code reason} of a refused notification (PU-08).
     *
     * @return Apple's word, or {@code null} when the body was empty or unreadable — which the catalog
     *     turns into {@code Unspecified} rather than guessing from the status alone
     */
    public String reasonOf(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode reason = root.get("reason");
            return reason == null || reason.isNull() ? null : reason.asString();
        } catch (JacksonException e) {
            return null;
        }
    }
}
