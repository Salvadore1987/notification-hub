package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * Rendered preview of a template with its segment count and cost (§11.2 "Шаблоны", FR-4.4).
 *
 * <p>Deliberately not strict: it exists to be used on a draft, so an unfilled merge field stays visible
 * as {@code {NAME}} in the text and is also listed in {@code missingVariables}. The sending path is the
 * strict one and refuses the same body with {@code TEMPLATE_VARIABLE_MISSING}.
 *
 * @param costs one quote per provider of the channel, each with the flag saying whether the router
 *     could actually pick it today
 */
public record TemplatePreviewResponse(
        String code,
        String channel,
        String locale,
        VersionDto version,
        TemplateVersionResponse.BodyDto rendered,
        List<String> missingVariables,
        List<ProviderCostDto> costs,
        SegmentationDto segmentation) {

    public record VersionDto(int number, String status) {}

    public record ProviderCostDto(String providerCode, String cost, boolean selectable) {}

    /** How the text splits into SMS segments (§18.3); zeroed for the other channels. */
    public record SegmentationDto(String encoding, int characterCount, int segments) {}
}
