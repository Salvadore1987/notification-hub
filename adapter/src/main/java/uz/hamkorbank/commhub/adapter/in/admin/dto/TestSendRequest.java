package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of a test send from the provider screen (§11.2 "Каналы и провайдеры", FR-7.4).
 *
 * <p>It goes through the ordinary pipeline — stream quotas, channel filters, provider credentials,
 * platform sandbox — because what is being checked is the pipeline, not a separate sending path. Three
 * things differ: the TEST flag (which is also what turns on the APNs sandbox and FCM's
 * {@code validate_only}, PU-13), a fresh dedup key, and the provider if one is named.
 *
 * @param provider pins the send to one provider by excluding the others, so a provider that cannot take
 *     it answers the ordinary {@code NO_ROUTE_AVAILABLE} rather than a special kind of failure
 */
public record TestSendRequest(
        String streamId, String channel, RecipientDto recipient, String provider, String subject, String text) {}
