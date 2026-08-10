package uz.hamkorbank.commhub.adapter.in.contract.dto;

import java.util.List;

/**
 * {@code content.email} of IK-03 (EM-01).
 *
 * @param html HTML body; together with {@code text} it becomes a {@code multipart/alternative}
 * @param from override of the configured sender address; absent uses the channel default
 */
public record EmailContentPayload(
        String subject, String html, String text, String from, List<AttachmentPayload> attachments) {}
