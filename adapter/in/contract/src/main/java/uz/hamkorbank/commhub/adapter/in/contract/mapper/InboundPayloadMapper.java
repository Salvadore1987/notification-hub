package uz.hamkorbank.commhub.adapter.in.contract.mapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.adapter.in.contract.dto.AllowedWindowPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.AttachmentPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.BatchItemPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.ChannelsPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.ContentPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.EmailContentPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.PushContentPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.PushTokenPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.RecipientPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.SmsContentPayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.TemplatePayload;
import uz.hamkorbank.commhub.adapter.in.contract.dto.TimingPayload;
import uz.hamkorbank.commhub.application.port.in.command.AddBatchItemsCommand;
import uz.hamkorbank.commhub.domain.exception.DomainException;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.TemplateRef;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;

/**
 * Turns the blocks of the inbound document into the value objects the commands are built from
 * (IK-03 → {@code SubmitMessageCommand}, AR-06).
 *
 * <p>Written as default methods for the same reason the application mappers are: every target is a
 * value object with a validating factory, and a generated setter-by-setter mapping cannot drive one.
 * What the generator would have given us — a field-by-field copy — is exactly what must not happen
 * here, because the copy has to go through {@code Msisdn}, {@code EmailAddress} and the content
 * records so that the rules of §18.1 and §9.3 are applied once, in the domain.
 *
 * <p>Every domain rejection is re-thrown as an {@link InboundContractException} naming the field of
 * IK-03 that carried the value; the caller therefore always learns which field to fix.
 */
@Mapper(componentModel = "spring")
public interface InboundPayloadMapper {

    /**
     * {@code recipient} → the addresses of the message (IK-03).
     *
     * <p>The MSISDN is taken <em>strictly</em> ({@code Msisdn.of}, not {@code Msisdn.normalize}): this is
     * the machine ingress, and FR-1.4, §9.1 and the published contract of §8.2 all say the same thing —
     * {@code ^9989\d{8}$}, no {@code +} and no spaces. Normalising here would accept, in silence, exactly
     * the documents the contract tells a source system are invalid, so its format bug would surface not
     * in integration testing but on the day its separator stops being an ASCII hyphen. Normalisation
     * stays where a human types the address (the panel's recipient CSV) and where an address is hashed.
     */
    default Recipient toRecipient(RecipientPayload payload, String path) {
        if (payload == null) {
            throw InboundContractException.missing(path);
        }
        List<PushToken> tokens = new ArrayList<>();
        List<PushTokenPayload> received = payload.pushTokens() == null ? List.of() : payload.pushTokens();
        for (int index = 0; index < received.size(); index++) {
            tokens.add(toPushToken(received.get(index), path + ".pushTokens[" + index + "]"));
        }
        List<PushToken> pushTokens = List.copyOf(tokens);
        return guard(
                path,
                () -> new Recipient(
                        blank(payload.clientId()) ? null : ClientId.of(payload.clientId()),
                        blank(payload.msisdn()) ? null : Msisdn.of(payload.msisdn()),
                        blank(payload.email()) ? null : EmailAddress.of(payload.email()),
                        pushTokens));
    }

    /** {@code content} → content per channel; {@code null} when the message relies on a template. */
    default MessageContents toContents(ContentPayload payload, String path) {
        if (payload == null) {
            return null;
        }
        List<MessageContent> contents = new ArrayList<>();
        if (payload.sms() != null) {
            contents.add(toSmsContent(payload.sms(), path + ".sms"));
        }
        if (payload.email() != null) {
            contents.add(toEmailContent(payload.email(), path + ".email"));
        }
        if (payload.push() != null) {
            contents.add(toPushContent(payload.push(), path + ".push"));
        }
        if (contents.isEmpty()) {
            return null;
        }
        return guard(path, () -> MessageContents.of(contents.toArray(MessageContent[]::new)));
    }

    /**
     * {@code channels} → the requested channel plan; {@code null} lets the Hub decide from the content
     * and the routing configuration (MP-03).
     */
    default ChannelPlan toChannelPlan(ChannelsPayload payload, String path) {
        if (payload == null
                || payload.requested() == null
                || payload.requested().isEmpty()) {
            return null;
        }
        List<String> requested = payload.requested();
        List<Channel> channels = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            channels.add(enumValue(Channel.class, requested.get(index), path + ".requested[" + index + "]"));
        }
        boolean chain = ChannelsPayload.FALLBACK_CHAIN.equalsIgnoreCase(payload.fallbackPolicy());
        if (payload.fallbackPolicy() != null && !blank(payload.fallbackPolicy()) && !chain) {
            throw InboundContractException.invalid(
                    path + ".fallbackPolicy", "unsupported policy " + payload.fallbackPolicy());
        }
        return guard(path, () -> {
            if (chain) {
                return ChannelPlan.fallbackChain(channels.toArray(Channel[]::new));
            }
            return channels.size() == 1
                    ? ChannelPlan.explicitChannel(channels.getFirst())
                    : ChannelPlan.moduleChoice(channels);
        });
    }

    /** {@code template} → the template reference with its merge variables (FR-4.3). */
    default TemplateRef toTemplate(TemplatePayload payload, String path) {
        if (payload == null || blank(payload.id())) {
            return null;
        }
        ContentLocale locale =
                blank(payload.locale()) ? null : enumValue(ContentLocale.class, payload.locale(), path + ".locale");
        Map<String, String> variables = payload.variables() == null ? Map.of() : payload.variables();
        return guard(path, () -> TemplateRef.of(TemplateCode.of(payload.id()), locale, variables));
    }

    /** {@code timing} → the send window and the lifetime of the message (FR-3.4, FR-8.5). */
    default Timing toTiming(TimingPayload payload, String path) {
        if (payload == null) {
            return null;
        }
        Instant sendAfter = instant(payload.sendAfter(), path + ".sendAfter");
        Instant sendBefore = instant(payload.sendBefore(), path + ".sendBefore");
        Duration ttl = payload.ttlSeconds() == null ? null : Duration.ofSeconds(payload.ttlSeconds());
        AllowedWindowPayload window = payload.allowedWindow();
        LocalTime start = window == null ? null : localTime(window.start(), path + ".allowedWindow.start");
        LocalTime end = window == null ? null : localTime(window.end(), path + ".allowedWindow.end");
        return guard(
                path,
                () -> new Timing(
                        sendAfter,
                        sendBefore,
                        ttl,
                        Boolean.TRUE.equals(payload.localTime()),
                        Boolean.TRUE.equals(payload.sendEvenly()),
                        start,
                        end));
    }

    /** One item of an uploaded chunk → the command form the batch use case expands (FR-1.6). */
    default AddBatchItemsCommand.Item toBatchItem(BatchItemPayload payload, String path) {
        if (payload == null) {
            throw InboundContractException.missing(path);
        }
        if (blank(payload.externalMessageId())) {
            throw InboundContractException.missing(path + ".externalMessageId");
        }
        return new AddBatchItemsCommand.Item(
                guard(path + ".externalMessageId", () -> ExternalMessageId.of(payload.externalMessageId())),
                toRecipient(payload.recipient(), path + ".recipient"),
                toContents(payload.content(), path + ".content"),
                toTemplate(payload.template(), path + ".template"),
                toChannelPlan(payload.channels(), path + ".channels"));
    }

    private PushToken toPushToken(PushTokenPayload payload, String path) {
        if (payload == null || blank(payload.token())) {
            throw InboundContractException.missing(path + ".token");
        }
        PushPlatform platform = enumValue(PushPlatform.class, payload.platform(), path + ".platform");
        return guard(path, () -> PushToken.of(payload.token(), platform));
    }

    private SmsContent toSmsContent(SmsContentPayload payload, String path) {
        if (blank(payload.text())) {
            throw InboundContractException.missing(path + ".text");
        }
        return guard(path, () -> SmsContent.of(payload.text(), payload.originator()));
    }

    private EmailContent toEmailContent(EmailContentPayload payload, String path) {
        if (blank(payload.subject())) {
            throw InboundContractException.missing(path + ".subject");
        }
        List<AttachmentPayload> received = payload.attachments() == null ? List.of() : payload.attachments();
        List<Attachment> attachments = new ArrayList<>();
        for (int index = 0; index < received.size(); index++) {
            attachments.add(toAttachment(received.get(index), path + ".attachments[" + index + "]"));
        }
        return guard(
                path,
                () -> new EmailContent(
                        payload.subject(),
                        payload.html(),
                        payload.text(),
                        List.copyOf(attachments),
                        blank(payload.from()) ? null : EmailAddress.of(payload.from())));
    }

    private Attachment toAttachment(AttachmentPayload payload, String path) {
        if (payload == null) {
            throw InboundContractException.missing(path);
        }
        return guard(
                path,
                () -> new Attachment(
                        payload.fileName(), payload.contentType(), payload.sizeBytes(), payload.contentRef()));
    }

    private PushContent toPushContent(PushContentPayload payload, String path) {
        if (blank(payload.title()) || blank(payload.body())) {
            throw InboundContractException.missing(path + (blank(payload.title()) ? ".title" : ".body"));
        }
        return guard(
                path,
                () -> new PushContent(
                        payload.title(),
                        payload.body(),
                        payload.data() == null ? Map.of() : payload.data(),
                        payload.deepLink(),
                        payload.image()));
    }

    /** Parses an enum constant of the contract, case-insensitively, naming the field when it is unknown. */
    static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String field) {
        if (blank(raw)) {
            throw InboundContractException.missing(field);
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw InboundContractException.invalid(field, "unknown value " + raw, e);
        }
    }

    /** Parses an ISO-8601 instant; {@code null} and blank mean "not set", which the contract allows. */
    static Instant instant(String raw, String field) {
        if (blank(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw InboundContractException.invalid(field, "is not an ISO-8601 instant", e);
        }
    }

    /** Parses a local time of the daily send window ({@code HH:mm} or {@code HH:mm:ss}). */
    static LocalTime localTime(String raw, String field) {
        if (blank(raw)) {
            return null;
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw InboundContractException.invalid(field, "is not a local time of the form HH:mm", e);
        }
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Runs a domain factory and reports its rejection as a violation of the field that carried the
     * value — the domain knows the rule, the contract knows the field name.
     */
    private static <T> T guard(String field, Supplier<T> factory) {
        try {
            return factory.get();
        } catch (DomainException e) {
            throw InboundContractException.invalid(field, e.getMessage(), e);
        }
    }
}
