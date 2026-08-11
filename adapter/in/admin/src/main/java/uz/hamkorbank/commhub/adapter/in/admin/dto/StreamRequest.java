package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of registering or updating a source stream (§11.2 "Входящие потоки", FR-1.3).
 *
 * <p>One record for both, because the form is one form. What differs is which fields the endpoint reads:
 * registration needs a name and an integration type, an update leaves alone whatever it was not given —
 * a {@code null} means "unchanged", not "clear".
 *
 * <p>Clearing therefore needs saying so, which is what {@code clearQuietHours} is for. Quiet hours are
 * the one setting where "no window" and "window unchanged" are both meaningful and both common, and a
 * form that expressed the first as an omission would make every partial update remove them.
 */
public record StreamRequest(
        String name,
        String integrationType,
        StreamResponse.StreamDefaultsDto defaults,
        QuotaDto quota,
        QuietHoursDto quietHours,
        Boolean clearQuietHours,
        RateLimitDto rateLimit) {

    public StreamRequest {
        clearQuietHours = clearQuietHours != null && clearQuietHours;
    }
}
