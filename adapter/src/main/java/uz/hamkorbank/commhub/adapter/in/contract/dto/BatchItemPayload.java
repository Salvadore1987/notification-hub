package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * One item of a batch chunk (§8.2 {@code POST /batches/{id}/items}, FR-1.6).
 *
 * <p>The envelope fields an item does not repeat — stream, channel, traffic class, timing — come from
 * the batch header; what stays per item is who it goes to and what it says.
 */
public record BatchItemPayload(
        String externalMessageId,
        RecipientPayload recipient,
        ContentPayload content,
        TemplatePayload template,
        ChannelsPayload channels) {}
