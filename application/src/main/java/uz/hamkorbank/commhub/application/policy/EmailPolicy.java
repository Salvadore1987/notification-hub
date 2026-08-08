package uz.hamkorbank.commhub.application.policy;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Limits an email must satisfy before it is routed (EM-01, FR-1.4).
 *
 * <p>EM-01 makes the attachment size a configuration item, and the check has to happen here rather than in
 * the SMTP adapter for two reasons. The corporate relay answers an oversized message with a hard refusal
 * after the whole body has been pushed across the wire, which for a bulk send is the expensive way to learn
 * something the Hub already knew; and a message rejected at validation carries a canonical reason back to
 * the source system (IR-01), while one refused by a relay carries an SMTP code nobody upstream reads.
 *
 * <p>Three ceilings rather than one, because they fail differently: a single huge file, many small ones, and
 * the total that the relay actually enforces. The total is the one that matters to the relay; the other two
 * exist so that the message that trips it can be named precisely.
 *
 * @param maxAttachments files one message may carry; {@code 0} forbids attachments entirely
 * @param maxAttachmentBytes size of one file
 * @param maxTotalBytes size of all files together, which is what the relay's own limit is expressed in
 */
public record EmailPolicy(int maxAttachments, long maxAttachmentBytes, long maxTotalBytes) {

    public static final int DEFAULT_MAX_ATTACHMENTS = 5;

    /** 10 MiB per file, the common ceiling of a corporate relay before MIME encoding overhead. */
    public static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    public static final long DEFAULT_MAX_TOTAL_BYTES = 20L * 1024 * 1024;

    public EmailPolicy {
        Guard.notNegative(maxAttachments, "EmailPolicy.maxAttachments");
        Guard.notNegative(maxAttachmentBytes, "EmailPolicy.maxAttachmentBytes");
        Guard.notNegative(maxTotalBytes, "EmailPolicy.maxTotalBytes");
    }

    public static EmailPolicy defaults() {
        return new EmailPolicy(DEFAULT_MAX_ATTACHMENTS, DEFAULT_MAX_ATTACHMENT_BYTES, DEFAULT_MAX_TOTAL_BYTES);
    }

    /**
     * Why this email may not be sent, if it may not (EM-01).
     *
     * @return the limit that was breached, phrased for the source system; empty when the content fits
     */
    public Optional<String> violation(EmailContent content) {
        Guard.notNull(content, "content");
        if (content.attachments().size() > maxAttachments) {
            return Optional.of("email carries %d attachments, the limit is %d (EM-01)"
                    .formatted(content.attachments().size(), maxAttachments));
        }
        for (Attachment attachment : content.attachments()) {
            if (attachment.sizeBytes() > maxAttachmentBytes) {
                // Имя файла — не ПДн и единственное, по чему отправитель узнает, какое вложение убрать.
                return Optional.of("attachment %s is %d bytes, the limit per file is %d (EM-01)"
                        .formatted(attachment.fileName(), attachment.sizeBytes(), maxAttachmentBytes));
            }
        }
        if (content.attachmentsSizeBytes() > maxTotalBytes) {
            return Optional.of("email attachments total %d bytes, the limit is %d (EM-01)"
                    .formatted(content.attachmentsSizeBytes(), maxTotalBytes));
        }
        return Optional.empty();
    }
}
