package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

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
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Delivery reports of the SMS Gate FEEDBACK service (SG-02, §18.2).
 *
 * <p>The report carries {@code login}, {@code key}, {@code id}, {@code code} and {@code description}
 * (§9.2). It arrives as form fields on some contours and as JSON on others, so both are read — the
 * callback endpoint hands over the raw body and the parsed parameters precisely for providers like this
 * one.
 *
 * <p>The {@code login}/{@code key} pair in the body is <em>not</em> what authenticates the report.
 * {@code CallbackGuard} does that, with the address allowlist and the shared secret agreed per provider
 * (SEC-07): credentials that travel in a body an attacker can also craft prove nothing, and comparing
 * them here would leak them into this class's error paths.
 *
 * <p>Code 6 (Unknown) produces no command. It is not an outcome — see {@link SmsGateStatusCatalog} —
 * and the reconciliation of SG-03 resolves those messages instead.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.smsgate", name = "enabled", havingValue = "true")
public class SmsGateCallbackTranslator implements ProviderCallbackTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(SmsGateCallbackTranslator.class);

    private final SmsGateProperties properties;
    private final SmsGateJson json;
    private final ClockPort clock;

    public SmsGateCallbackTranslator(SmsGateProperties properties, SmsGateJson json, ClockPort clock) {
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
        Field field = fieldsOf(body, parameters);
        String id = field.read("id").orElseThrow(() -> InboundContractException.missing("id"));
        String code = field.read("code").orElseThrow(() -> InboundContractException.missing("code"));
        Optional<MessageStatus> canonical = SmsGateStatusCatalog.canonical(code);
        if (canonical.isEmpty()) {
            LOG.info(
                    "SMS Gate reported code {} ({}) for id {}: nothing to apply, reconciliation resolves it (SG-03)",
                    code,
                    SmsGateStatusCatalog.describe(code),
                    id);
            return List.of();
        }
        ProviderStatusCommand report = new ProviderStatusCommand(
                providerCode(),
                ProviderMessageId.of(id),
                null,
                canonical.get(),
                SmsGateStatusCatalog.describe(code),
                field.read("description").orElse(null),
                null,
                clock.now());
        // Код 7 (InBlackList) — это и статус сообщения, и приговор адресу (§18.2, FR-5.1).
        return List.of(
                SmsGateStatusCatalog.invalidatesRecipient(code)
                        ? report.suppressing(SuppressionReason.PROVIDER_BLACKLIST)
                        : report);
    }

    /**
     * Reads a field from the JSON body when there is one, and from the form parameters otherwise.
     *
     * <p>FEEDBACK is documented as a POST of five fields (§9.2) without saying how they are encoded, and
     * the two encodings are what a webhook actually receives in practice.
     */
    private Field fieldsOf(String body, Map<String, String> parameters) {
        JsonNode root = json.readOrNull(body);
        Map<String, String> form = parameters == null ? Map.of() : parameters;
        if ((root == null || !root.isObject()) && form.isEmpty()) {
            throw InboundContractException.invalid("body", "is not an SMS Gate delivery report (§18.2)");
        }
        return new Field(root, form);
    }

    /** One report's fields, wherever the provider put them. */
    private record Field(JsonNode json, Map<String, String> form) {

        Optional<String> read(String name) {
            return SmsGateJson.scalar(json, name).or(() -> Optional.ofNullable(form.get(name))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty()));
        }
    }
}
