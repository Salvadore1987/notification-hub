package uz.hamkorbank.commhub.adapter.in.contract.dto;

import java.util.Map;

/**
 * {@code template} block of IK-03 (FR-4.1, FR-4.3).
 *
 * @param id template code as the source system knows it; the Hub resolves the published version itself
 * @param locale {@code RU}, {@code UZ} or {@code EN}; absent means {@code RU}, and a locale the
 *     template has no published version in falls back to {@code RU} as well
 * @param variables merge fields; a missing one is rejected in strict mode (FR-4.3)
 */
public record TemplatePayload(String id, String locale, Map<String, String> variables) {}
