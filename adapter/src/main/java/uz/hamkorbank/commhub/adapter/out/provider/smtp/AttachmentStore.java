package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads the bytes an attachment reference points at (EM-01).
 *
 * <p>Every failure here is permanent by construction: a reference that names nothing, escapes the mounted
 * directory or has outgrown the ceiling will name nothing, escape and be too large on the next attempt too.
 * That is why it raises {@link AttachmentNotAvailableException} instead of an I/O error the send path would
 * have to guess about — the message is refused with a reason the source system can act on rather than
 * retried against a file that is not coming back.
 */
@Component
public class AttachmentStore {

    private final AttachmentStoreProperties properties;

    public AttachmentStore(AttachmentStoreProperties properties) {
        this.properties = Guard.notNull(properties, "properties");
    }

    /**
     * Bytes of one attachment.
     *
     * @throws AttachmentNotAvailableException when the reference cannot be resolved, read, or is too large
     */
    public byte[] read(Attachment attachment) {
        Guard.notNull(attachment, "attachment");
        if (!properties.isConfigured()) {
            throw new AttachmentNotAvailableException(
                    "this deployment carries no attachments: commhub.email.attachments.directory is not set");
        }
        Path file = resolve(attachment.contentRef());
        try {
            long size = Files.size(file);
            if (size > properties.maxBytes()) {
                throw new AttachmentNotAvailableException("attachment %s is %d bytes, the store reads at most %d"
                        .formatted(attachment.fileName(), size, properties.maxBytes()));
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            // Путь в сообщение не кладём: он и есть ссылка, а её уже назвал вызывающий.
            throw new AttachmentNotAvailableException(
                    "attachment %s could not be read: %s".formatted(attachment.fileName(), e.getMessage()));
        }
    }

    /**
     * Turns a reference into a path inside the mounted directory.
     *
     * <p>An absolute reference or one climbing out with {@code ..} is refused rather than normalised: a
     * message is submitted by a source system, and a source system that can name any file on the pod could
     * mail the Bank's private keys to a customer.
     */
    private Path resolve(String contentRef) {
        Path root = Path.of(properties.directory()).toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = root.resolve(contentRef).normalize();
        } catch (InvalidPathException e) {
            throw new AttachmentNotAvailableException("attachment reference is not a path: " + e.getMessage());
        }
        if (!resolved.startsWith(root)) {
            throw new AttachmentNotAvailableException("attachment reference points outside the attachment store");
        }
        return resolved;
    }

    /** The referenced bytes are not available and will not become available (EM-01). */
    public static class AttachmentNotAvailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public AttachmentNotAvailableException(String message) {
            super(message);
        }
    }
}
