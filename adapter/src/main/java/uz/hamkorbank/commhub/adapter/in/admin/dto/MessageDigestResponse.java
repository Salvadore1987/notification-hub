package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * One line of the message list (§11.2 "Сообщения").
 *
 * @param recipient masked for every role except {@code ADMIN} and {@code OPERATOR} (DB-04, SEC-06);
 *     what the client receives is already what that role may see, so a masked panel cannot be turned
 *     back into an unmasked one from the browser
 */
public record MessageDigestResponse(
        String messageId,
        String streamId,
        String externalMessageId,
        String channel,
        String status,
        String recipient,
        String acceptedAt,
        RoutingResponse routing) {

    /** How the line was routed and what it cost (FR-2.1, FR-6.2). */
    public record RoutingResponse(
            String provider,
            String channel,
            String reason,
            String batchId,
            String correlationId,
            String cost,
            int segments,
            String terminalAt) {}
}
