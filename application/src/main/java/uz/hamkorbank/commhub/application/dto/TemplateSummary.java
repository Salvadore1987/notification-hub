package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A template as a catalogue listing shows it (FR-4.1, FR-4.6).
 *
 * <p>Bodies are left out on purpose: a listing of ~470 templates × 3 locales would otherwise ship every
 * text of the Bank to render one table, and the row an operator opens comes back as a {@link TemplateView}.
 *
 * @param publishedLocales locales that currently have a sendable version — the column that answers
 *     "is the Uzbek wording live yet?" (FR-4.1)
 */
public record TemplateSummary(
        TemplateId templateId,
        TemplateCode code,
        Channel channel,
        String direction,
        String owner,
        TemplateCatalogStatus catalogStatus,
        List<ContentLocale> publishedLocales) {

    public TemplateSummary {
        Guard.notNull(templateId, "TemplateSummary.templateId");
        Guard.notNull(code, "TemplateSummary.code");
        Guard.notNull(channel, "TemplateSummary.channel");
        Guard.notNull(catalogStatus, "TemplateSummary.catalogStatus");
        publishedLocales = Guard.copyOf(publishedLocales);
    }
}
