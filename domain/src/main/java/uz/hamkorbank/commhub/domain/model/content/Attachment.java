package uz.hamkorbank.commhub.domain.model.content;

import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Email attachment metadata (EM-01).
 *
 * <p>The bytes themselves are not part of the domain model: {@link #contentRef()} points at the
 * stored payload, which keeps aggregates small and keeps binary content out of the message table.
 *
 * @param fileName name shown to the recipient
 * @param contentType MIME type
 * @param sizeBytes size of the stored payload; the configured limit is enforced by the use case
 * @param contentRef reference to the stored bytes (object storage key or database reference)
 */
public record Attachment(String fileName, String contentType, long sizeBytes, String contentRef) {

    public static final int MAX_FILE_NAME_LENGTH = 255;

    public Attachment {
        Guard.notBlank(fileName, "Attachment.fileName");
        Guard.maxLength(fileName, MAX_FILE_NAME_LENGTH, "Attachment.fileName");
        Guard.notBlank(contentType, "Attachment.contentType");
        Guard.notNegative(sizeBytes, "Attachment.sizeBytes");
        Guard.notBlank(contentRef, "Attachment.contentRef");
    }
}
