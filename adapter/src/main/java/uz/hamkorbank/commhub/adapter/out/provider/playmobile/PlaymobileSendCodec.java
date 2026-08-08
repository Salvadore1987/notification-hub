package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileProperties.Sending;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderTemplateBinding;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Writes the {@code /send} document and reads the error answer (§9.1, PM-01).
 *
 * <p>Field by field rather than through mapped records, for the same reason as the outbound status
 * codec: the request has a nested shape with more fields than a record of this project may carry, and
 * what matters about it is that it reads as the contract of §9.1 — the field names, in their groups,
 * where a reviewer holding the provider's documentation can check them one by one.
 *
 * <p>Only fields with a value are written. Playmobile validates the fields it receives (§18.1 codes
 * 301–306, 403, 407), so an explicit {@code null} where the Hub simply has nothing to say is a
 * rejected message rather than a defaulted one — the opposite convention from the outbound topic,
 * where an absent field would change the shape a consumer sees.
 *
 * <p>Single and bulk sends are the same document: Playmobile takes an array of messages either way
 * (§9.1). {@code priority} and {@code timing} are per request and not per message, so a bulk send takes
 * them from its first element; the dispatcher only ever groups messages of one traffic class, which is
 * what makes that safe (TC-01).
 */
@Component
public class PlaymobileSendCodec {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final PlaymobileJson json;

    public PlaymobileSendCodec(PlaymobileJson json) {
        this.json = Guard.notNull(json, "json");
    }

    /** Renders one or many messages as a single {@code /send} request. */
    public String encode(List<PlaymobileSend> sends, Sending sending) {
        Guard.notNull(sending, "sending");
        Guard.isTrue(sends != null && !sends.isEmpty(), "a Playmobile send needs at least one message");
        ObjectNode root = json.object();
        ArrayNode messages = root.putArray("messages");
        sends.forEach(send -> writeMessage(messages.addObject(), send, sending));
        SmsSubmission first = sends.getFirst().submission();
        root.put(
                "priority",
                sending.priorityOf(
                        first.context().trafficClass(), first.context().priority()));
        writeTiming(root, first.timing());
        return json.write(root);
    }

    /** One element of {@code messages[]} (§9.1). */
    private static void writeMessage(ObjectNode node, PlaymobileSend send, Sending sending) {
        SmsSubmission submission = send.submission();
        node.put("recipient", submission.recipient().value());
        node.put("message-id", send.providerMessageId().value());
        ObjectNode sms = node.putObject("sms");
        originatorOf(submission, sending).ifPresent(originator -> sms.put("originator", originator));
        ttlSecondsOf(submission, sending).ifPresent(ttl -> sms.put("ttl", ttl));
        writeContent(sms.putObject("content"), submission);
    }

    /**
     * Text, or a reference to a template registered on the Playmobile side (FR-4.5).
     *
     * <p>An unapproved binding is ignored and the rendered text is sent instead: a template Playmobile
     * has not signed off is a template Playmobile will refuse (§18.1 code 206).
     */
    private static void writeContent(ObjectNode content, SmsSubmission submission) {
        ProviderTemplateBinding binding = submission
                .templateBindingOptional()
                .filter(ProviderTemplateBinding::approved)
                .orElse(null);
        if (binding == null) {
            content.put("text", submission.content().text());
            return;
        }
        content.put("template-id", binding.providerTemplateId());
        ObjectNode variables = content.putObject("variables");
        binding.variables().forEach(variables::put);
    }

    /** {@code timing} block; omitted entirely when the message says nothing about when to send it. */
    private static void writeTiming(ObjectNode root, Timing timing) {
        if (timing == null || isEmpty(timing)) {
            return;
        }
        ObjectNode node = root.putObject("timing");
        if (timing.localTime()) {
            node.put("localtime", true);
        }
        if (timing.sendEvenly()) {
            node.put("send-evenly", true);
        }
        if (timing.sendAfter() != null) {
            node.put("start-datetime", isoInstant(timing.sendAfter()));
        }
        if (timing.sendBefore() != null) {
            node.put("end-datetime", isoInstant(timing.sendBefore()));
        }
        if (timing.allowedStartTime() != null) {
            node.put("allowed-starttime", time(timing.allowedStartTime()));
            node.put("allowed-endtime", time(timing.allowedEndTime()));
        }
    }

    /**
     * The {@code error_code} of a refused request (§18.1).
     *
     * <p>Both spellings are accepted. The SRS prints {@code error_code} and the provider's own samples
     * use {@code error-code}; reading either costs one line and saves an integration day.
     */
    public Optional<PlaymobileError> readError(String body) {
        JsonNode root = json.readOrNull(body);
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        String code =
                text(root, "error-code").or(() -> text(root, "error_code")).orElse(null);
        if (code == null) {
            return Optional.empty();
        }
        String description = text(root, "error-description")
                .or(() -> text(root, "error_description"))
                .orElse(null);
        return Optional.of(new PlaymobileError(code, description));
    }

    private static Optional<String> originatorOf(SmsSubmission submission, Sending sending) {
        String fromMessage = submission.content().originator();
        if (fromMessage != null && !fromMessage.isBlank()) {
            return Optional.of(fromMessage);
        }
        return Optional.ofNullable(sending.originator()).filter(value -> !value.isBlank());
    }

    private static Optional<Long> ttlSecondsOf(SmsSubmission submission, Sending sending) {
        return submission
                .timing()
                .ttlOptional()
                .or(() -> Optional.ofNullable(sending.defaultTtl()))
                .map(Duration::toSeconds)
                .filter(seconds -> seconds > 0L);
    }

    private static boolean isEmpty(Timing timing) {
        return !timing.localTime()
                && !timing.sendEvenly()
                && timing.sendAfter() == null
                && timing.sendBefore() == null
                && timing.allowedStartTime() == null;
    }

    private static String isoInstant(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String time(LocalTime value) {
        return value.format(TIME);
    }

    private static Optional<String> text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        String value = node.asString();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * The body of a refused {@code /send} (§18.1).
     *
     * @param code {@code error_code}, kept as text: it is an identifier, and the delivery attempt stores
     *     it next to codes from providers that are not numeric at all
     */
    public record PlaymobileError(String code, String description) {

        public PlaymobileError {
            Guard.notBlank(code, "PlaymobileError.code");
        }

        public String describe() {
            return PlaymobileErrorCatalog.describe(code, description);
        }
    }
}
