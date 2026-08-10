package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateProperties.Sending;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Writes the SMS Gate requests and reads their answers (§9.2, SG-01, SG-02).
 *
 * <p>Three requests, all with {@code login} and {@code key} in the body: {@code /api/v2/send} for one
 * message, {@code /api/v2/send_msgs} for a chunk, and {@code /api/v2/search} for the reconciliation of
 * SG-03. There is no {@code timing} and no {@code template-id} block anywhere — SMS Gate has neither,
 * which is precisely why the Hub renders the text and holds deferred sends itself (SG-01).
 *
 * <p>Reading is deliberately forgiving about types and about where a list lives. The API answers the
 * single and the bulk form with differently shaped documents and is inconsistent about whether
 * {@code id} and {@code code} are numbers or strings; the Hub stores both as text either way. The exact
 * field names of {@code /api/v2/search} are the one thing §9.2 does not print, so that reader accepts
 * the shapes the other two endpoints use and is confirmed against the provider at integration (SG-03).
 */
@Component
public class SmsGateSendCodec {

    private final SmsGateJson json;

    public SmsGateSendCodec(SmsGateJson json) {
        this.json = Guard.notNull(json, "json");
    }

    /** {@code POST /api/v2/send} — one message (§9.2). */
    public String encodeSend(SmsGateCredentials credentials, SmsSubmission submission, Sending sending) {
        ObjectNode root = credentialed(credentials);
        senderOf(submission, sending).ifPresent(sender -> root.put("sender", sender));
        root.put("phone", submission.recipient().value());
        root.put("text", submission.content().text());
        root.put("weight", sending.weightOf(submission.context().trafficClass()));
        return json.write(root);
    }

    /**
     * {@code POST /api/v2/send_msgs} — a chunk (§9.2).
     *
     * <p>{@code sender} and {@code weight} are per request, so a chunk shares them; the dispatcher only
     * groups messages of one traffic class, which is what makes a single weight correct (TC-01).
     * {@code seq} is the position in the chunk and is how the per-element answers are matched back.
     */
    public String encodeSendBatch(SmsGateCredentials credentials, List<SmsSubmission> submissions, Sending sending) {
        Guard.isTrue(submissions != null && !submissions.isEmpty(), "an SMS Gate batch needs at least one message");
        ObjectNode root = credentialed(credentials);
        SmsSubmission first = submissions.getFirst();
        senderOf(first, sending).ifPresent(sender -> root.put("sender", sender));
        root.put("weight", sending.weightOf(first.context().trafficClass()));
        ArrayNode messages = root.putArray("messages");
        for (int seq = 0; seq < submissions.size(); seq++) {
            SmsSubmission submission = submissions.get(seq);
            ObjectNode node = messages.addObject();
            node.put("seq", seq + 1);
            node.put("phone", submission.recipient().value());
            node.put("text", submission.content().text());
        }
        return json.write(root);
    }

    /** {@code POST /api/v2/search} — statuses of one number around a moment (SG-03, §9.2). */
    public String encodeSearch(SmsGateCredentials credentials, String phone, long dateEpochSeconds) {
        ObjectNode root = credentialed(credentials);
        root.put("phone", phone);
        root.put("date", dateEpochSeconds);
        return json.write(root);
    }

    /** {@code status.code} plus the {@code id} and {@code parts} of an accepted single send (§9.2). */
    public Optional<SmsGateAnswer> readAnswer(String body) {
        JsonNode root = json.readOrNull(body);
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        Optional<String> code = statusCode(root);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SmsGateAnswer(
                code.get(),
                statusDescription(root).orElseGet(() -> SmsGateResponseCatalog.describe(code.get())),
                SmsGateJson.scalar(root, "id").orElse(null)));
    }

    /**
     * Per-element answers of a chunk: {@code seq}, {@code id}, {@code code}, {@code parts} (§9.2).
     *
     * <p>An element may report its verdict as a state word instead of a code ({@code duplicate},
     * {@code blacklist}, …, §18.2); both are folded onto the same code table by
     * {@link SmsGateResponseCatalog#codeOfItemState(String)} so callers classify once.
     */
    public List<SmsGateItem> readBatchAnswer(String body) {
        JsonNode root = json.readOrNull(body);
        JsonNode messages = root == null ? null : root.get("messages");
        if (messages == null || !messages.isArray()) {
            return List.of();
        }
        List<SmsGateItem> items = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            JsonNode node = messages.get(i);
            String code = SmsGateJson.scalar(node, "code")
                    .or(() -> SmsGateJson.scalar(node, "status").map(SmsGateResponseCatalog::codeOfItemState))
                    .orElse(null);
            items.add(new SmsGateItem(
                    SmsGateJson.scalar(node, "seq").map(SmsGateSendCodec::toInt).orElse(i + 1),
                    SmsGateJson.scalar(node, "id").orElse(null),
                    code));
        }
        return List.copyOf(items);
    }

    /**
     * Entries of a {@code /api/v2/search} answer (SG-03).
     *
     * <p>Accepts the list under {@code messages} or under {@code result}, and a bare array as the whole
     * document: §9.2 documents the query of this endpoint but not the shape of what it returns, so the
     * reader covers the forms the sibling endpoints use and is pinned during integration.
     */
    public List<SmsGateItem> readSearch(String body) {
        JsonNode root = json.readOrNull(body);
        if (root == null) {
            return List.of();
        }
        JsonNode list = root.isArray() ? root : firstArray(root);
        if (list == null) {
            return List.of();
        }
        List<SmsGateItem> entries = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode node = list.get(i);
            entries.add(new SmsGateItem(
                    SmsGateJson.scalar(node, "seq").map(SmsGateSendCodec::toInt).orElse(i + 1),
                    SmsGateJson.scalar(node, "id").orElse(null),
                    SmsGateJson.scalar(node, "code").orElse(null)));
        }
        return List.copyOf(entries);
    }

    private static JsonNode firstArray(JsonNode root) {
        JsonNode messages = root.get("messages");
        if (messages != null && messages.isArray()) {
            return messages;
        }
        JsonNode result = root.get("result");
        return result != null && result.isArray() ? result : null;
    }

    private ObjectNode credentialed(SmsGateCredentials credentials) {
        Guard.notNull(credentials, "credentials");
        ObjectNode root = json.object();
        root.put("login", credentials.login());
        root.put("key", credentials.key());
        return root;
    }

    private static Optional<String> senderOf(SmsSubmission submission, Sending sending) {
        String fromMessage = submission.content().originator();
        if (fromMessage != null && !fromMessage.isBlank()) {
            return Optional.of(fromMessage);
        }
        return Optional.ofNullable(sending.sender()).filter(value -> !value.isBlank());
    }

    /** {@code status.code}, also accepted at the root — the API is not consistent about the nesting. */
    private static Optional<String> statusCode(JsonNode root) {
        JsonNode status = root.get("status");
        if (status != null && status.isObject()) {
            return SmsGateJson.scalar(status, "code");
        }
        return SmsGateJson.scalar(root, "status").or(() -> SmsGateJson.scalar(root, "code"));
    }

    private static Optional<String> statusDescription(JsonNode root) {
        JsonNode status = root.get("status");
        if (status != null && status.isObject()) {
            return SmsGateJson.scalar(status, "description");
        }
        return SmsGateJson.scalar(root, "description");
    }

    private static int toInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The answer to a single send (§9.2).
     *
     * @param id provider-side identifier; present only when the message was accepted
     */
    public record SmsGateAnswer(String code, String description, String id) {

        public SmsGateAnswer {
            Guard.notBlank(code, "SmsGateAnswer.code");
        }

        public boolean isSuccess() {
            return SmsGateResponseCatalog.isSuccess(code);
        }
    }

    /**
     * One element of a chunk answer or of a search answer (§9.2, §18.2).
     *
     * @param seq position in the request, 1-based
     * @param code {@code status.code} of the element, or the code equivalent to its state word
     */
    public record SmsGateItem(int seq, String id, String code) {

        public boolean isSuccess() {
            return SmsGateResponseCatalog.isSuccess(code);
        }
    }
}
