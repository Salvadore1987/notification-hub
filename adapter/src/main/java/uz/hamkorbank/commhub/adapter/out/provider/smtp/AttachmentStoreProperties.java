package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the bytes behind an attachment reference live (EM-01).
 *
 * <p>The domain keeps only the metadata of an attachment — name, type, size and a {@code contentRef} — so
 * that a message row stays a message row and not a blob (see {@code Attachment}). Somebody still has to
 * turn that reference into bytes at send time, and this says where to look.
 *
 * <p>A mounted directory rather than object storage, because the Bank has not chosen one and inventing a
 * dependency on S3 to hold a handful of statement PDFs would be the wrong way round. The reference is a path
 * relative to {@link #directory()}, resolved with the same rule the secret store uses for its file scheme:
 * anything that escapes the directory is refused rather than read. When the Bank does choose a store, this
 * class is what changes, and nothing above it (AR-04).
 *
 * @param directory root the references are resolved against; empty means the Hub carries no attachments,
 *     which is the deployment default — the MVP sends notifications, not documents
 * @param maxBytes ceiling on what is read from disk; the validator has already applied the policy of EM-01,
 *     and this is the second, cruder line in case a file grew after the message was accepted
 */
@ConfigurationProperties("commhub.email.attachments")
public record AttachmentStoreProperties(String directory, Long maxBytes) {

    public static final long DEFAULT_MAX_BYTES = 25L * 1024 * 1024;

    public AttachmentStoreProperties {
        directory = directory == null || directory.isBlank() ? null : directory.trim();
        maxBytes = maxBytes == null || maxBytes <= 0 ? DEFAULT_MAX_BYTES : maxBytes;
    }

    public static AttachmentStoreProperties disabled() {
        return new AttachmentStoreProperties(null, null);
    }

    public boolean isConfigured() {
        return directory != null;
    }
}
