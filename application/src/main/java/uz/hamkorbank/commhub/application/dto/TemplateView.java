package uz.hamkorbank.commhub.application.dto;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A template card with all its versions, as the administration screens see it (FR-4.1, FR-4.5).
 *
 * <p>Every version is carried, not only the published one: the screen an operator edits on is the history
 * of the text — which draft is waiting for review, what the previous published wording was — and that
 * history is the reason versions exist.
 *
 * @param versions versions of every locale, ordered by locale and then by version number
 */
public record TemplateView(
        TemplateId templateId,
        TemplateCode code,
        Channel channel,
        String direction,
        String owner,
        TemplateCatalogStatus catalogStatus,
        List<TemplateVersionView> versions,
        List<Template.ProviderMapping> providerMappings) {

    public TemplateView {
        Guard.notNull(templateId, "TemplateView.templateId");
        Guard.notNull(code, "TemplateView.code");
        Guard.notNull(channel, "TemplateView.channel");
        Guard.notNull(catalogStatus, "TemplateView.catalogStatus");
        versions = Guard.copyOf(versions);
        providerMappings = Guard.copyOf(providerMappings);
    }

    public Optional<String> directionOptional() {
        return Optional.ofNullable(direction);
    }

    public Optional<String> ownerOptional() {
        return Optional.ofNullable(owner);
    }
}
