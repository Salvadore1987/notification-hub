package uz.hamkorbank.commhub.adapter.out.persistence.json;

import uz.hamkorbank.commhub.domain.model.content.Attachment;

/** An email attachment inside {@code message.contents}; the file itself lives behind {@code contentRef} (EM-01). */
public record AttachmentJson(String fileName, String contentType, long sizeBytes, String contentRef) {

    public static AttachmentJson of(Attachment attachment) {
        return new AttachmentJson(
                attachment.fileName(), attachment.contentType(), attachment.sizeBytes(), attachment.contentRef());
    }

    public Attachment toDomain() {
        return new Attachment(fileName, contentType, sizeBytes, contentRef);
    }
}
