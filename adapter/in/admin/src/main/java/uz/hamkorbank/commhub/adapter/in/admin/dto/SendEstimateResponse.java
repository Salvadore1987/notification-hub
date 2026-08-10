package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * What the send would cost, shown before it is confirmed (§11.2 "Отправка", FR-4.4).
 *
 * @param missingVariables merge fields no row filled in; those rows would be rejected on the real send
 * @param rejection why nothing can be sent, when that is the answer; the confirm button stays disabled
 * @param failures rows of the uploaded file that could not be read, with their line numbers
 */
public record SendEstimateResponse(
        long recipients,
        long segments,
        String estimatedCost,
        String provider,
        TemplateVersionDto template,
        List<String> missingVariables,
        RejectionDto rejection,
        List<ImportResultResponse.FailureDto> failures) {

    /** The exact version that was priced, so the screen names what it is about to send (FR-4.2). */
    public record TemplateVersionDto(int version, String status) {}

    /** Why the send cannot proceed (IR-01 vocabulary). */
    public record RejectionDto(String reason, String detail) {}
}
