package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * A rate limit, in the shape the panel reads and writes (IR-02, PR-01).
 *
 * <p>Boxed and defaulted rather than primitive: Jackson refuses to map an absent field onto a primitive,
 * and a form that sends two of the three limits is a form, not a malformed request. 0 means no ceiling
 * in every one of them, which is what an omitted field should mean.
 *
 * @param perRecipientPerHour ceiling on how often one address may be written to; 0 means no ceiling
 */
public record RateLimitDto(Integer tps, Integer perMinute, Integer perRecipientPerHour) {

    public RateLimitDto {
        tps = tps == null ? 0 : tps;
        perMinute = perMinute == null ? 0 : perMinute;
        perRecipientPerHour = perRecipientPerHour == null ? 0 : perRecipientPerHour;
    }
}
