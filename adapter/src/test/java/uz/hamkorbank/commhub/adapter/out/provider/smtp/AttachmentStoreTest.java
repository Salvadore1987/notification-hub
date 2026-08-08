package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uz.hamkorbank.commhub.domain.model.content.Attachment;

/** What the Hub is allowed to attach, and what a reference must never be able to reach (EM-01). */
class AttachmentStoreTest {

    @Test
    @DisplayName("EM-01: a reference inside the mounted directory is read")
    void readsAReference(@TempDir Path directory) throws Exception {
        // Arrange
        Files.write(directory.resolve("statement.pdf"), "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        AttachmentStore store = new AttachmentStore(new AttachmentStoreProperties(directory.toString(), null));

        // Act
        byte[] bytes = store.read(new Attachment("Выписка.pdf", "application/pdf", 8, "statement.pdf"));

        // Assert
        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4");
    }

    @Test
    @DisplayName("a reference climbing out of the directory is refused, not normalised")
    void refusesTraversal(@TempDir Path directory) {
        // Arrange
        AttachmentStore store = new AttachmentStore(new AttachmentStoreProperties(directory.toString(), null));
        Attachment escaping = new Attachment("key.pem", "application/x-pem-file", 8, "../../secrets/key.pem");

        // Act + Assert
        assertThatExceptionOfType(AttachmentStore.AttachmentNotAvailableException.class)
                .isThrownBy(() -> store.read(escaping))
                .withMessageContaining("outside");
    }

    @Test
    @DisplayName("EM-01: a file over the store ceiling is refused rather than streamed into a message")
    void refusesAnOversizedFile(@TempDir Path directory) throws Exception {
        // Arrange
        Files.write(directory.resolve("big.bin"), new byte[64]);
        AttachmentStore store = new AttachmentStore(new AttachmentStoreProperties(directory.toString(), 16L));

        // Act + Assert
        assertThatExceptionOfType(AttachmentStore.AttachmentNotAvailableException.class)
                .isThrownBy(() -> store.read(new Attachment("big.bin", "application/octet-stream", 64, "big.bin")))
                .withMessageContaining("the store reads at most");
    }

    @Test
    @DisplayName("a deployment without an attachment directory says so instead of sending an empty message")
    void refusesWhenNoDirectoryIsConfigured() {
        // Arrange
        AttachmentStore store = new AttachmentStore(AttachmentStoreProperties.disabled());

        // Act + Assert
        assertThatExceptionOfType(AttachmentStore.AttachmentNotAvailableException.class)
                .isThrownBy(() -> store.read(new Attachment("a.pdf", "application/pdf", 1, "a.pdf")))
                .withMessageContaining("commhub.email.attachments.directory");
    }
}
