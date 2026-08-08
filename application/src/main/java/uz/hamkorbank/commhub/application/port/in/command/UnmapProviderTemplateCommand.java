package uz.hamkorbank.commhub.application.port.in.command;

import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/** Drops the mapping of a template onto a provider-side template (FR-4.5). */
public record UnmapProviderTemplateCommand(Actor actor, TemplateCode code, ProviderCode providerCode) {

    public UnmapProviderTemplateCommand {
        Guard.notNull(actor, "UnmapProviderTemplateCommand.actor");
        Guard.notNull(code, "UnmapProviderTemplateCommand.code");
        Guard.notNull(providerCode, "UnmapProviderTemplateCommand.providerCode");
    }
}
