package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * One version of a template (§11.2 "Шаблоны", FR-4.1, FR-4.2).
 *
 * @param variables merge fields found in the body; the form builds its preview inputs from these
 */
public record TemplateVersionResponse(
        String versionId,
        int version,
        String locale,
        BodyDto body,
        String status,
        List<String> variables,
        ReviewDto review) {

    /** The wording; {@code html} only ever alongside {@code text}, and only on an email template. */
    public record BodyDto(String subject, String text, String html) {}

    /**
     * Who wrote it and who let it out (FR-4.2).
     *
     * <p>Both names come from the authenticated actor rather than from a form field, and publication
     * needs them to differ — which is the whole of the four-eyes rule and why they are shown here.
     */
    public record ReviewDto(String createdBy, String reviewedBy, String publishedAt) {}
}
