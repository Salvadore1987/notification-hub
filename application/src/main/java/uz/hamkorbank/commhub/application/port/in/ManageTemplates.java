package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.TemplateVersionView;
import uz.hamkorbank.commhub.application.dto.TemplateView;
import uz.hamkorbank.commhub.application.port.in.command.CreateTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.MapProviderTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.SaveTemplateVersionCommand;
import uz.hamkorbank.commhub.application.port.in.command.TemplateStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.TemplateVersionStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.UnmapProviderTemplateCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateTemplateCommand;

/**
 * Administration of the template catalogue: cards, localised versions, review workflow, provider-side
 * mapping (FR-4.1, FR-4.2, FR-4.5).
 *
 * <p>One interface per aggregate rather than one per operation, as with the routing configuration: all of
 * these load the template in order to change it, and they share its transaction and its audit entry.
 *
 * <p>Rejections here are exceptions, not verdicts (IR-01 is about the pipeline): editing a template is an
 * interactive operator session, so a duplicate code is {@code ConfigurationConflictException} (409) and an
 * unknown code is {@code NotFoundException} (404). A maker/checker violation surfaces as the domain's own
 * validation error (FR-4.2).
 */
public interface ManageTemplates {

    /** Registers a new template card; the code must be free across the catalogue (FR-4.1). */
    TemplateView create(CreateTemplateCommand command);

    /** Edits the attributes of a card — direction and owner (FR-4.1, §18.4). */
    TemplateView update(UpdateTemplateCommand command);

    /** Archives a card or brings it back into the working catalogue (FR-4.1). */
    TemplateView changeState(TemplateStateCommand command);

    /** Creates the next draft of a locale, or rewrites the draft that is already there (FR-4.1). */
    TemplateVersionView saveVersion(SaveTemplateVersionCommand command);

    /** Moves a version through the review workflow; publication needs a second person (FR-4.1, FR-4.2). */
    TemplateVersionView changeVersionState(TemplateVersionStateCommand command);

    /** Records the template registered at a provider and whether it has been approved (FR-4.5). */
    TemplateView mapProviderTemplate(MapProviderTemplateCommand command);

    /** Drops such a mapping (FR-4.5). */
    TemplateView unmapProviderTemplate(UnmapProviderTemplateCommand command);
}
