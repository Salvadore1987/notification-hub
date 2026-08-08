package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.TemplateImportResult;
import uz.hamkorbank.commhub.application.port.in.command.ImportTemplatesCommand;

/**
 * Bulk load of the Bank's existing template base (~470 templates, FR-4.6).
 *
 * <p>A use case and not a SQL script, for the reason the whole template layer exists: the rules that make
 * a template valid — merge-field syntax, one published version per locale, maker/checker — live in the
 * domain, and an {@code INSERT} would go round every one of them. Nothing imported this way can be in a
 * state the admin panel could not have produced.
 *
 * <p>Re-running the same file imports nothing new: a body that is already the published wording of its
 * locale is skipped. That is what makes the migration something an operator can retry after fixing a few
 * rows, instead of a one-shot they have to get right.
 */
public interface ImportTemplates {

    TemplateImportResult importTemplates(ImportTemplatesCommand command);
}
