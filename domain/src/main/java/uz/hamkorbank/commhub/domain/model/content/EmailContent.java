package uz.hamkorbank.commhub.domain.model.content;

import java.nio.charset.StandardCharsets;
import java.util.List;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Email payload: subject, HTML and/or plain-text body, attachments and the sender (SRS §5.2, EM-01).
 *
 * <p>At least one body variant must be present; the SMTP adapter builds a
 * {@code multipart/alternative} message when both are (EM-01).
 *
 * @param from {@code null} means "take the provider default sender"
 */
public record EmailContent(
        String subject, String htmlBody, String textBody, List<Attachment> attachments, EmailAddress from)
        implements MessageContent {

    public static final int MAX_SUBJECT_LENGTH = 998;

    public EmailContent {
        Guard.notBlank(subject, "EmailContent.subject");
        Guard.maxLength(subject, MAX_SUBJECT_LENGTH, "EmailContent.subject");
        attachments = Guard.copyOf(attachments);
        Guard.isTrue(
                (htmlBody != null && !htmlBody.isBlank()) || (textBody != null && !textBody.isBlank()),
                "EmailContent requires an htmlBody, a textBody or both");
    }

    public static EmailContent ofText(String subject, String textBody) {
        return new EmailContent(subject, null, textBody, List.of(), null);
    }

    public static EmailContent ofHtml(String subject, String htmlBody, String textBody) {
        return new EmailContent(subject, htmlBody, textBody, List.of(), null);
    }

    /** Returns a copy with the template-rendered subject and bodies (FR-4.3). */
    public EmailContent withRendered(String renderedSubject, String renderedHtmlBody, String renderedTextBody) {
        return new EmailContent(renderedSubject, renderedHtmlBody, renderedTextBody, attachments, from);
    }

    public boolean isMultipart() {
        return htmlBody != null && !htmlBody.isBlank() && textBody != null && !textBody.isBlank();
    }

    public long attachmentsSizeBytes() {
        return attachments.stream().mapToLong(Attachment::sizeBytes).sum();
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public int payloadSizeBytes() {
        int size = subject.getBytes(StandardCharsets.UTF_8).length;
        if (htmlBody != null) {
            size += htmlBody.getBytes(StandardCharsets.UTF_8).length;
        }
        if (textBody != null) {
            size += textBody.getBytes(StandardCharsets.UTF_8).length;
        }
        return size;
    }
}
