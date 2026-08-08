package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Correspondence between a template of the Hub and one registered at a provider (FR-4.5).
 *
 * <p>Approval with the operator is an organisational process outside the Hub — Playmobile registers the
 * text on its side and answers by mail — so {@code approved} is a fact an operator records, not something
 * the Hub can discover. Until it is recorded, the mapping exists and is not yet trusted.
 *
 * @param providerTemplateId provider-side identifier, e.g. the Playmobile {@code template-id}
 */
public record MapProviderTemplateCommand(
        Actor actor, TemplateCode code, ProviderCode providerCode, String providerTemplateId, boolean approved) {

    public MapProviderTemplateCommand {
        Guard.notNull(actor, "MapProviderTemplateCommand.actor");
        Guard.notNull(code, "MapProviderTemplateCommand.code");
        Guard.notNull(providerCode, "MapProviderTemplateCommand.providerCode");
        Guard.notBlank(providerTemplateId, "MapProviderTemplateCommand.providerTemplateId");
    }
}
