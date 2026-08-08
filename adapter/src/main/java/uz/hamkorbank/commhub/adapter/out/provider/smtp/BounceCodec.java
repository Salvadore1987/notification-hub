package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads a non-delivery report out of a mail message (EM-02, RFC 3464).
 *
 * <p>A proper DSN is a {@code multipart/report} with three parts: something a human can read, a
 * {@code message/delivery-status} with the machine-readable verdict, and the original message or its
 * headers. The verdict is what is parsed here, and the third part is what makes attribution possible — it
 * returns the {@code Message-ID} and the {@code X-Comm-Message-Id} the Hub wrote (EM-01).
 *
 * <p>The delivery-status part is read as text rather than through a DSN library. Its grammar is a list of
 * {@code field: value} lines, i.e. exactly the grammar of a header block, and pulling in a parser for it
 * would buy nothing but a dependency.
 *
 * <p>Not every bounce is a DSN. Some servers, and most legacy corporate gateways, return a human-readable
 * message with the original quoted inside. The fallback handles those the only way a machine honestly can:
 * find the returned {@code X-Comm-Message-Id}, find an enhanced status code somewhere in the text, and
 * report both. If either is missing the message is left alone rather than guessed at — an unattributed
 * report that silently suppressed the wrong address would be the worst possible outcome (FR-5.1).
 */
@Component
public class BounceCodec {

    private static final String DELIVERY_STATUS_TYPE = "message/delivery-status";
    private static final String RFC822_TYPE = "message/rfc822";
    private static final String RFC822_HEADERS_TYPE = "text/rfc822-headers";

    /** Enhanced status code of RFC 3463 anywhere in a human-readable bounce. */
    private static final Pattern ENHANCED_STATUS = Pattern.compile("\\b([245])\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    private static final Pattern HUB_MESSAGE_ID =
            Pattern.compile("(?im)^" + SmtpMessageCodec.MESSAGE_ID_HEADER + "\\s*:\\s*(\\S+)\\s*$");

    private static final Pattern MESSAGE_ID = Pattern.compile("(?im)^Message-ID\\s*:\\s*(<[^>]+>)\\s*$");

    /** How much of a report is read looking for the original identifiers; a returned body can be large. */
    private static final int MAX_SCANNED_CHARACTERS = 256 * 1024;

    /**
     * Reads one message from the bounce mailbox.
     *
     * @return the report, or empty when the message is not a non-delivery report the Hub can attribute
     */
    public Optional<BounceReport> parse(Part message) throws MessagingException, IOException {
        Guard.notNull(message, "message");
        Parts parts = collect(message, new Parts());
        Optional<BounceReport> dsn = fromDeliveryStatus(parts);
        return dsn.isPresent() ? dsn : fromText(parts);
    }

    /** The RFC 3464 path: a machine-readable verdict. */
    private static Optional<BounceReport> fromDeliveryStatus(Parts parts) {
        if (parts.deliveryStatus == null) {
            return Optional.empty();
        }
        Map<String, String> fields = readFields(parts.deliveryStatus);
        String action = fields.get("action");
        if (action == null) {
            return Optional.empty();
        }
        return Optional.of(new BounceReport(
                hubMessageId(parts).orElse(null),
                originalMessageId(parts).orElse(null),
                addressOf(fields.get("final-recipient"), fields.get("original-recipient")),
                action.trim().toLowerCase(Locale.ROOT),
                fields.get("status") == null ? null : fields.get("status").trim(),
                fields.get("diagnostic-code")));
    }

    /**
     * The fallback path: a bounce written for a person.
     *
     * <p>Deliberately conservative. Without an identifier there is no message to apply anything to, and
     * without a status code there is nothing to apply — so both are required, and a report missing either
     * stays in the mailbox for someone to look at.
     */
    private static Optional<BounceReport> fromText(Parts parts) {
        Optional<MessageId> hubMessageId = hubMessageId(parts);
        Matcher status = ENHANCED_STATUS.matcher(parts.text());
        if (hubMessageId.isEmpty() || !status.find()) {
            return Optional.empty();
        }
        String enhanced = status.group();
        return Optional.of(new BounceReport(
                hubMessageId.get(),
                originalMessageId(parts).orElse(null),
                null,
                enhanced.charAt(0) == '2' ? BounceReport.ACTION_DELIVERED : BounceReport.ACTION_FAILED,
                enhanced,
                null));
    }

    /**
     * The Hub identifier, from the returned headers or from the {@code Message-ID} the Hub wrote (EM-02).
     *
     * <p>Two sources because reports differ in what they return: the whole original message, only its
     * headers, or only a quoted {@code Message-ID}. The Hub's own header is preferred where it survived —
     * a {@code Message-ID} can be rewritten by a gateway on the way out, a custom header rarely is.
     */
    private static Optional<MessageId> hubMessageId(Parts parts) {
        Matcher header = HUB_MESSAGE_ID.matcher(parts.text());
        if (header.find()) {
            try {
                return Optional.of(MessageId.fromString(header.group(1).trim()));
            } catch (RuntimeException e) {
                // Заголовок есть, но это не наш идентификатор — идём к Message-ID.
                return SmtpMessageCodec.messageIdFrom(originalMessageId(parts).orElse(null));
            }
        }
        return SmtpMessageCodec.messageIdFrom(originalMessageId(parts).orElse(null));
    }

    private static Optional<String> originalMessageId(Parts parts) {
        Matcher matcher = MESSAGE_ID.matcher(parts.text());
        return matcher.find() ? Optional.of(matcher.group(1).trim()) : Optional.empty();
    }

    /** {@code Final-Recipient: rfc822; user@example.com} — the type prefix is not part of the address. */
    private static String addressOf(String finalRecipient, String originalRecipient) {
        String value = finalRecipient == null ? originalRecipient : finalRecipient;
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(';');
        String address = separator < 0 ? value : value.substring(separator + 1);
        return address.trim().replace("<", "").replace(">", "");
    }

    /** Parses a {@code field: value} block, folding continuation lines the way a header block does. */
    private static Map<String, String> readFields(String block) {
        Map<String, String> fields = new LinkedHashMap<>();
        String lastKey = null;
        for (String line : block.split("\r?\n", -1)) {
            if (lastKey != null && !line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                fields.put(lastKey, fields.get(lastKey) + " " + line.trim());
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            lastKey = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            // Первое вхождение поля выигрывает: у отчёта на несколько получателей блоки идут подряд,
            // а Hub шлёт по одному адресу за письмо — второй блок был бы уже про чужое сообщение.
            fields.putIfAbsent(lastKey, line.substring(colon + 1).trim());
        }
        return fields;
    }

    /**
     * Walks the MIME tree once, keeping the two things a report is read for.
     *
     * <p>Everything textual is accumulated into one buffer, because the identifiers can be in the returned
     * headers, in the returned message, or quoted in the human-readable part, and which of those a given
     * mail server produces is not something the Hub can decide.
     */
    private static Parts collect(Part part, Parts parts) throws MessagingException, IOException {
        if (part.isMimeType(DELIVERY_STATUS_TYPE) && parts.deliveryStatus == null) {
            parts.deliveryStatus = read(part);
            parts.append(parts.deliveryStatus);
            return parts;
        }
        if (part.isMimeType(RFC822_TYPE) || part.isMimeType(RFC822_HEADERS_TYPE) || part.isMimeType("text/*")) {
            parts.append(read(part));
            return parts;
        }
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int index = 0; index < multipart.getCount(); index++) {
                collect(multipart.getBodyPart(index), parts);
            }
        }
        return parts;
    }

    private static String read(Part part) throws MessagingException, IOException {
        try (InputStream stream = part.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Accumulator of one walk: the machine-readable verdict, and everything readable as text. */
    private static final class Parts {

        private final StringBuilder scanned = new StringBuilder();
        private String deliveryStatus;

        private void append(String fragment) {
            if (scanned.length() >= MAX_SCANNED_CHARACTERS) {
                return;
            }
            scanned.append(fragment).append('\n');
        }

        private String text() {
            return scanned.toString();
        }
    }
}
