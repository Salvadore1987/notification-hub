package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * One template card with its versions and provider mappings (§11.2 "Шаблоны", FR-4.1, FR-4.5).
 *
 * @param catalogStatus {@code ACTIVE} or {@code ARCHIVED} — deleting a template is archiving it, since
 *     its code is in the history of every message ever rendered from it
 */
public record TemplateResponse(
        String templateId,
        String code,
        String channel,
        String direction,
        String owner,
        String catalogStatus,
        List<TemplateVersionResponse> versions,
        List<ProviderMappingDto> providerMappings) {

    /** The template as it is registered at one provider (FR-4.5). */
    public record ProviderMappingDto(String providerCode, String providerTemplateId, boolean approved) {}
}
