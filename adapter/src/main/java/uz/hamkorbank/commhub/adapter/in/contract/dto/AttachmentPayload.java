package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * One entry of {@code content.email.attachments} (EM-01).
 *
 * <p>The bytes never travel in the message: {@code contentRef} points at the object store, which keeps
 * the Kafka record small enough for the broker and the attachment out of the message table.
 */
public record AttachmentPayload(String fileName, String contentType, long sizeBytes, String contentRef) {}
