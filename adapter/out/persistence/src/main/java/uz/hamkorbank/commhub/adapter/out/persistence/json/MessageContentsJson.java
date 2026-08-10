package uz.hamkorbank.commhub.adapter.out.persistence.json;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;

/**
 * {@link MessageContents} inside the {@code message.contents} column (MP-02).
 *
 * <p>One field per channel instead of a polymorphic list with a type tag: the sealed hierarchy has a
 * fixed, small set of members, and a named field makes a stored row readable without a decoder. Adding
 * a channel (AR-05) adds a field here — a new column-level shape, not a migration of existing rows.
 */
public record MessageContentsJson(SmsJson sms, EmailJson email, PushJson push) {

    public static MessageContentsJson of(MessageContents contents) {
        return new MessageContentsJson(
                SmsJson.of((SmsContent) contents.forChannel(Channel.SMS).orElse(null)),
                EmailJson.of((EmailContent) contents.forChannel(Channel.EMAIL).orElse(null)),
                PushJson.of((PushContent) contents.forChannel(Channel.PUSH).orElse(null)));
    }

    public MessageContents toDomain() {
        Map<Channel, MessageContent> byChannel = new EnumMap<>(Channel.class);
        if (sms != null) {
            byChannel.put(Channel.SMS, sms.toDomain());
        }
        if (email != null) {
            byChannel.put(Channel.EMAIL, email.toDomain());
        }
        if (push != null) {
            byChannel.put(Channel.PUSH, push.toDomain());
        }
        return new MessageContents(byChannel);
    }

    /** {@link SmsContent}: the text as it will be sent, plus the originator when the stream fixes one. */
    public record SmsJson(String text, String originator) {

        public static SmsJson of(SmsContent content) {
            return content == null ? null : new SmsJson(content.text(), content.originator());
        }

        public SmsContent toDomain() {
            return new SmsContent(text, originator);
        }
    }

    /** {@link EmailContent} with its attachments (EM-01). */
    public record EmailJson(
            String subject, String htmlBody, String textBody, List<AttachmentJson> attachments, String from) {

        public static EmailJson of(EmailContent content) {
            if (content == null) {
                return null;
            }
            return new EmailJson(
                    content.subject(),
                    content.htmlBody(),
                    content.textBody(),
                    content.attachments().stream().map(AttachmentJson::of).toList(),
                    content.from() == null ? null : content.from().value());
        }

        public EmailContent toDomain() {
            return new EmailContent(
                    subject,
                    htmlBody,
                    textBody,
                    attachments == null
                            ? List.of()
                            : attachments.stream().map(AttachmentJson::toDomain).toList(),
                    from == null ? null : EmailAddress.of(from));
        }
    }

    /** {@link PushContent} with its data payload (PU-02). */
    public record PushJson(String title, String body, Map<String, String> data, String deepLink, String image) {

        public static PushJson of(PushContent content) {
            if (content == null) {
                return null;
            }
            return new PushJson(content.title(), content.body(), content.data(), content.deepLink(), content.image());
        }

        public PushContent toDomain() {
            return new PushContent(title, body, data == null ? Map.of() : data, deepLink, image);
        }
    }
}
