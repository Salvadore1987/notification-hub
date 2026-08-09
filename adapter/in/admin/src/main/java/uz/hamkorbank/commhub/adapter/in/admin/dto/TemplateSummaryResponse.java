package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * One line of the template catalogue (§11.2 "Шаблоны", FR-4.1).
 *
 * <p>Carries no bodies. A catalogue page is a list of what exists, and the bodies of fifty templates in
 * three locales is a payload nobody on that screen reads.
 */
public record TemplateSummaryResponse(
        String templateId,
        String code,
        String channel,
        String direction,
        String owner,
        String catalogStatus,
        List<String> publishedLocales) {}
