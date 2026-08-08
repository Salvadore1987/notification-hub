package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Builds the MIME message handed to the relay (EM-01).
 *
 * <p>Three shapes, chosen by what the content carries. A body with both alternatives becomes
 * {@code multipart/alternative} with the plain text first and the HTML second — the order is the standard's,
 * and it is what makes a client that cannot render HTML show the text rather than nothing. Attachments wrap
 * that in a {@code multipart/mixed}. A body with one alternative and no files is a single part.
 *
 * <p>The {@code Message-ID} is the Hub's own identifier, not one the relay invents, and that single decision
 * is what makes EM-02 work: a non-delivery report quotes the {@code Message-ID} of the message it is about,
 * so a bounce arriving hours later still names the row it belongs to. {@code X-Comm-Message-Id} carries the
 * same identifier in a header of our own, for the relay's operators and for the reports that return the
 * original headers instead of quoting the id (EM-01).
 */
@Component
public class SmtpMessageCodec {

    /** End-to-end identifier required by EM-01. */
    public static final String MESSAGE_ID_HEADER = "X-Comm-Message-Id";

    public static final String CORRELATION_ID_HEADER = "X-Comm-Correlation-Id";

    /** Lets the mail team see which class of traffic a message belongs to without opening it (TC-01). */
    public static final String TRAFFIC_CLASS_HEADER = "X-Comm-Traffic-Class";

    /** Marks a configuration test send, so a mail gateway can drop it if it wants to (FR-7.4). */
    public static final String TEST_HEADER = "X-Comm-Test";

    private static final String UTF_8 = "UTF-8";
    private static final String TEXT_MIME = "text/plain; charset=UTF-8";
    private static final String HTML_MIME = "text/html; charset=UTF-8";

    private final AttachmentStore attachments;

    public SmtpMessageCodec(AttachmentStore attachments) {
        this.attachments = Guard.notNull(attachments, "attachments");
    }

    /**
     * Assembles one message.
     *
     * @param sending sender settings with the runtime overlay already applied (AD-07)
     * @param sentAt value of the {@code Date} header; comes from {@code ClockPort} like every other instant
     * @throws MessagingException when the content cannot be expressed as MIME — a malformed sender address,
     *     an attachment that cannot be read
     */
    public MimeMessage encode(Session session, EmailSubmission submission, SmtpProperties.Sending sending, Date sentAt)
            throws MessagingException {
        Guard.notNull(session, "session");
        Guard.notNull(submission, "submission");
        Guard.notNull(sending, "sending");
        EmailContent content = submission.content();
        MimeMessage message = new IdentifiedMessage(session, messageIdOf(submission, sending));
        message.setFrom(from(content, sending));
        replyTo(sending).ifPresent(replyTo -> setReplyTo(message, replyTo));
        message.setRecipient(
                Message.RecipientType.TO, address(submission.recipient().value(), null));
        message.setSubject(content.subject(), UTF_8);
        message.setSentDate(sentAt);
        message.setHeader(MESSAGE_ID_HEADER, submission.messageId().toString());
        message.setHeader(
                CORRELATION_ID_HEADER, submission.context().correlationId().toString());
        message.setHeader(
                TRAFFIC_CLASS_HEADER, submission.context().trafficClass().name());
        if (submission.context().test()) {
            message.setHeader(TEST_HEADER, "true");
        }
        body(message, content);
        message.saveChanges();
        return message;
    }

    /**
     * The {@code Message-ID} of a message, in the form the delivery reports will quote it in (EM-02).
     *
     * <p>Local part is the Hub's message identifier; the domain is the sender's, because a
     * {@code Message-ID} whose domain nobody owns is a well-known spam signal.
     */
    public static String messageIdOf(EmailSubmission submission, SmtpProperties.Sending sending) {
        return "<%s@%s>".formatted(submission.messageId(), domainOf(submission, sending));
    }

    /** Same identifier as the ack carries it: no angle brackets, and short enough for the column. */
    public static String providerMessageIdOf(EmailSubmission submission, SmtpProperties.Sending sending) {
        String qualified = "%s@%s".formatted(submission.messageId(), domainOf(submission, sending));
        return qualified.length() <= ProviderMessageId.MAX_LENGTH
                ? qualified
                : submission.messageId().toString();
    }

    /** The Hub message identifier carried by a {@code Message-ID} the Hub itself wrote (EM-02). */
    public static Optional<MessageId> messageIdFrom(String messageIdHeader) {
        if (messageIdHeader == null || messageIdHeader.isBlank()) {
            return Optional.empty();
        }
        String value = messageIdHeader.trim().replace("<", "").replace(">", "");
        int at = value.indexOf('@');
        String localPart = at < 0 ? value : value.substring(0, at);
        try {
            return Optional.of(MessageId.fromString(localPart));
        } catch (RuntimeException e) {
            // Чужой Message-ID — не ошибка: письмо мог отправить не Hub, а отчёт всё равно пришёл к нам.
            return Optional.empty();
        }
    }

    private static String domainOf(EmailSubmission submission, SmtpProperties.Sending sending) {
        return Optional.ofNullable(submission.content().from())
                .map(EmailAddress::domain)
                .or(() -> sending.fromOptional().map(SmtpMessageCodec::domainOf))
                .orElseGet(() -> submission.recipient().domain());
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 ? address : address.substring(at + 1);
    }

    /** The message's own sender wins over the configured one: a stream may send as its own department. */
    private static InternetAddress from(EmailContent content, SmtpProperties.Sending sending)
            throws MessagingException {
        String address = Optional.ofNullable(content.from())
                .map(EmailAddress::value)
                .or(sending::fromOptional)
                .orElseThrow(() -> new MessagingException(
                        "no sender: the message carries none and commhub.provider.smtp.sending.from is not set"));
        return address(address, sending.fromNameOptional().orElse(null));
    }

    private static Optional<String> replyTo(SmtpProperties.Sending sending) {
        return sending.replyToOptional();
    }

    private static void setReplyTo(MimeMessage message, String replyTo) {
        try {
            message.setReplyTo(new InternetAddress[] {address(replyTo, null)});
        } catch (MessagingException e) {
            throw new IllegalStateException("configured reply-to is not an address: " + replyTo, e);
        }
    }

    private static InternetAddress address(String address, String personalName) throws AddressException {
        InternetAddress parsed = new InternetAddress(address, false);
        parsed.validate();
        if (personalName != null && !personalName.isBlank()) {
            try {
                parsed.setPersonal(personalName, UTF_8);
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("UTF-8 is not supported by this JVM", e);
            }
        }
        return parsed;
    }

    private void body(MimeMessage message, EmailContent content) throws MessagingException {
        if (content.attachments().isEmpty()) {
            setReadableContent(message, content);
            return;
        }
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart readable = new MimeBodyPart();
        setReadableContent(readable, content);
        mixed.addBodyPart(readable);
        for (Attachment attachment : content.attachments()) {
            mixed.addBodyPart(attachmentPart(attachment));
        }
        message.setContent(mixed);
    }

    /**
     * Puts the readable body on a part: one alternative, or both as {@code multipart/alternative} (EM-01).
     *
     * <p>Takes a {@link Part} rather than a message so that the attachment case can nest exactly the same
     * body inside its {@code multipart/mixed} instead of building a second version of it.
     */
    private static void setReadableContent(Part part, EmailContent content) throws MessagingException {
        if (!content.isMultipart()) {
            boolean html = content.textBody() == null || content.textBody().isBlank();
            part.setContent(html ? content.htmlBody() : content.textBody(), html ? HTML_MIME : TEXT_MIME);
            return;
        }
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setContent(content.textBody(), TEXT_MIME);
        alternative.addBodyPart(plain);
        MimeBodyPart html = new MimeBodyPart();
        html.setContent(content.htmlBody(), HTML_MIME);
        // Порядок обязателен: последняя альтернатива — предпочтительная, и клиент, умеющий HTML,
        // показывает её. Обратный порядок означал бы, что все читают plain text (RFC 2046 §5.1.4).
        alternative.addBodyPart(html);
        part.setContent(alternative);
    }

    private MimeBodyPart attachmentPart(Attachment attachment) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        byte[] bytes = attachments.read(attachment);
        part.setDataHandler(new DataHandler(new ByteArrayAttachment(attachment, bytes)));
        part.setFileName(attachment.fileName());
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }

    /** The bytes of one attachment, already in memory; the store has applied its own ceiling (EM-01). */
    private record ByteArrayAttachment(Attachment attachment, byte[] bytes) implements DataSource {

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public OutputStream getOutputStream() {
            // Вложение читается, а не пишется: DataSource двусторонний, эта половина нам не нужна.
            throw new UnsupportedOperationException("an attachment is read-only");
        }

        @Override
        public String getContentType() {
            return attachment.contentType();
        }

        @Override
        public String getName() {
            return attachment.fileName();
        }
    }

    /**
     * A message whose {@code Message-ID} the Hub decides (EM-02).
     *
     * <p>jakarta.mail generates one in {@code saveChanges()} unless this hook says otherwise, and a generated
     * one would be unknown to us the moment a bounce quoted it back.
     */
    private static final class IdentifiedMessage extends MimeMessage {

        private final String messageId;

        private IdentifiedMessage(Session session, String messageId) {
            super(session);
            this.messageId = messageId;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", messageId);
        }
    }

    /** The message as it goes on the wire; used for the DKIM signature (EM-03) and by the tests. */
    public static byte[] serialize(MimeMessage message) throws MessagingException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            message.writeTo(buffer);
        } catch (IOException e) {
            throw new MessagingException("the message could not be serialised", e);
        }
        return buffer.toByteArray();
    }

    /** Convenience for the tests and the signer: the serialised message as text. */
    public static String asString(MimeMessage message) throws MessagingException {
        return new String(serialize(message), StandardCharsets.UTF_8);
    }
}
