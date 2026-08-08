package uz.hamkorbank.commhub.application.port.in.command;

import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Bulk load of the Bank's existing template base (~470 templates, FR-4.6).
 *
 * <p>One row is one localised body; rows sharing a code build one template with several locales. The rows
 * are already parsed — whoever read the file (CSV today, an admin upload later) owns the file format, and
 * the use case owns what a template is (AR-06).
 *
 * <p>{@code author} is written into every imported version and {@code approver} publishes them. They must
 * differ, which is the maker/checker rule applied honestly to a migration: the import is the author, the
 * person who ran it is the reviewer (FR-4.2). Leaving {@code approver} empty imports everything as drafts,
 * which is the safe default for a dry run.
 *
 * @param author name recorded as the author of every imported version
 * @param approver who publishes the imported versions; {@code null} leaves them as drafts
 */
public record ImportTemplatesCommand(Actor actor, String author, String approver, List<Row> rows) {

    public ImportTemplatesCommand {
        Guard.notNull(actor, "ImportTemplatesCommand.actor");
        Guard.notBlank(author, "ImportTemplatesCommand.author");
        rows = Guard.copyOf(rows);
        Guard.isTrue(!rows.isEmpty(), "ImportTemplatesCommand.rows is empty");
        if (approver != null) {
            Guard.isTrue(
                    !approver.equalsIgnoreCase(author),
                    "maker/checker: the approver of an import may not be its author (FR-4.2)");
        }
    }

    public Optional<String> approverOptional() {
        return Optional.ofNullable(approver);
    }

    /** Whether imported versions are published straight away (FR-4.1, FR-4.2). */
    public boolean publishes() {
        return approver != null;
    }

    /**
     * One localised body of the file being imported (FR-4.6).
     *
     * @param html HTML alternative of an email body; {@code null} everywhere else (EM-01)
     * @param direction business direction the template belongs to (§18.4)
     * @param owner owning unit; {@code null} keeps whatever the existing card has
     */
    public record Row(
            String code,
            Channel channel,
            ContentLocale locale,
            String subject,
            String text,
            String html,
            String direction,
            String owner) {

        public Row {
            Guard.notBlank(code, "Row.code");
            Guard.notNull(channel, "Row.channel");
            Guard.notNull(locale, "Row.locale");
            Guard.notBlank(text, "Row.text");
        }
    }
}
