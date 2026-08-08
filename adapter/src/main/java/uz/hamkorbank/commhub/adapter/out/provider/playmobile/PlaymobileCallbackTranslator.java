package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.adapter.in.callback.ProviderCallbackTranslator;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Playmobile delivery reports pushed to {@code <partner-url>/status} (PM-02, §18.1).
 *
 * <p>The report carries {@code message-id}, {@code channel}, {@code status}, {@code status-date} and
 * {@code description} (§9.1). The {@code message-id} is the one this adapter generated when it sent the
 * message, so it matches the delivery attempt directly.
 *
 * <p>Two failure modes, treated differently on purpose:
 *
 * <ul>
 *   <li>a report without a {@code message-id} or without a {@code status} is a contract violation —
 *       there is nothing to apply it to, so it is refused and Playmobile is told what was missing;
 *   <li>a report with a {@code status} word §18.1 does not account for is <em>dropped with a warning</em>
 *       and answered 200. §18.1 itself says the exact set of values is fixed during the integration; a
 *       word we have not seen before is far more likely to be a new intermediate state than a lost
 *       delivery, and refusing it would make Playmobile retry that report forever while the operator
 *       reads the warning and adds it to {@link PlaymobileStatusCatalog}.
 * </ul>
 *
 * <p>Also accepts an array of reports: providers batch delivery receipts, and doing so is cheaper for
 * both sides than one request per message.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.playmobile", name = "enabled", havingValue = "true")
public class PlaymobileCallbackTranslator implements ProviderCallbackTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(PlaymobileCallbackTranslator.class);

    /** {@code status-date} as §9.1 prints it, and as the SMS-Broker samples show it. */
    private static final DateTimeFormatter SPACE_SEPARATED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Only the SMS channel is in scope; {@code call} is a Playmobile feature the MVP does not use (§9.1). */
    private static final String SMS_CHANNEL = "sms";

    private final PlaymobileProperties properties;
    private final PlaymobileJson json;
    private final ClockPort clock;

    public PlaymobileCallbackTranslator(PlaymobileProperties properties, PlaymobileJson json, ClockPort clock) {
        this.properties = Guard.notNull(properties, "properties");
        this.json = Guard.notNull(json, "json");
        this.clock = Guard.notNull(clock, "clock");
    }

    @Override
    public ProviderCode providerCode() {
        return ProviderCode.of(properties.providerCode());
    }

    @Override
    public List<ProviderStatusCommand> translate(String body, Map<String, String> parameters) {
        JsonNode root = json.readOrNull(body);
        if (root == null) {
            throw InboundContractException.invalid("body", "is not a Playmobile delivery report (§18.1)");
        }
        List<ProviderStatusCommand> reports = new ArrayList<>();
        if (root.isArray()) {
            for (int i = 0; i < root.size(); i++) {
                translateOne(root.get(i)).ifPresent(reports::add);
            }
        } else {
            translateOne(root).ifPresent(reports::add);
        }
        return List.copyOf(reports);
    }

    private Optional<ProviderStatusCommand> translateOne(JsonNode node) {
        if (!node.isObject()) {
            throw InboundContractException.invalid("body", "must be a report object or an array of them");
        }
        String providerMessageId =
                text(node, "message-id").or(() -> text(node, "message_id")).orElse(null);
        if (providerMessageId == null) {
            throw InboundContractException.missing("message-id");
        }
        String providerStatus = text(node, "status").orElse(null);
        if (providerStatus == null) {
            throw InboundContractException.missing("status");
        }
        String channel = text(node, "channel").orElse(SMS_CHANNEL);
        if (!SMS_CHANNEL.equalsIgnoreCase(channel)) {
            LOG.warn(
                    "Playmobile report for message-id {} names channel {}, which the Hub does not send on",
                    providerMessageId,
                    channel);
            return Optional.empty();
        }
        Optional<MessageStatus> canonical = PlaymobileStatusCatalog.canonical(providerStatus);
        if (canonical.isEmpty()) {
            LOG.warn(
                    "Playmobile reported status '{}' for message-id {}: §18.1 does not list it, the report is dropped",
                    providerStatus,
                    providerMessageId);
            return Optional.empty();
        }
        return Optional.of(new ProviderStatusCommand(
                providerCode(),
                ProviderMessageId.of(providerMessageId),
                null,
                canonical.get(),
                providerStatus,
                text(node, "description").orElse(null),
                // §18.1 не описывает статуса, который осуждал бы сам номер: у Playmobile чёрный список свой
                // (blacklist-id, §9.1) и в DLR не приходит. Suppression по его отчётам не заводится.
                null,
                occurredAt(node)));
    }

    /**
     * {@code status-date}, ISO 8601 UTC per §9.1 with the space-separated form the samples use.
     *
     * <p>A report with an unreadable date is applied with the reception time rather than refused: the
     * status is the part that matters, and the alternative is a delivery confirmation the Bank never
     * records because a provider changed its date format.
     */
    private Instant occurredAt(JsonNode node) {
        Optional<String> raw = text(node, "status-date").or(() -> text(node, "status_date"));
        if (raw.isEmpty()) {
            return clock.now();
        }
        String value = raw.get();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return spaceSeparated(value);
        }
    }

    private Instant spaceSeparated(String value) {
        try {
            return LocalDateTime.parse(value, SPACE_SEPARATED).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            LOG.warn("Playmobile sent an unreadable status-date '{}'; the report is timed at reception", value);
            return clock.now();
        }
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        String text = value.asString();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }
}
