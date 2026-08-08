package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.Map;

/**
 * Body of the template preview with segment count and cost (§11.2 "Шаблоны", FR-4.4).
 *
 * @param version {@code null} previews the latest version of the locale, published or not — the point
 *     of the screen is to look at a draft before it goes anywhere
 * @param variables merge values to fill in; whatever is missing stays visible as {@code {NAME}}
 */
public record TemplatePreviewRequest(String locale, Integer version, Map<String, String> variables) {}
