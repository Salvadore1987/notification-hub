/**
 * Inbound adapter for the one-off import of the Bank's existing template base (FR-4.6).
 *
 * <p>An adapter and not a SQL script: the rules that make a template valid — merge-field syntax, version
 * numbering per locale, one published version per locale, maker/checker — live in the domain, and an
 * {@code INSERT} would go round all of them. The file format lives here, what a template is lives in the
 * core (AR-06).
 *
 * <p>The file is a CSV with a header line; the delimiter is configurable and defaults to {@code ;}, which is
 * what Excel writes in a RU locale. Columns are addressed by name, so the Bank's own bookkeeping columns can
 * stay in the sheet:
 *
 * <pre>
 * code;channel;locale;subject;text;direction;owner
 * OTP_LOGIN;SMS;RU;;"Код подтверждения: {CODE}";Чакана;retail-team
 * OTP_LOGIN;SMS;UZ;;"Tasdiqlash kodi: {CODE}";Чакана;retail-team
 * </pre>
 *
 * <p>Required: {@code code}, {@code channel}, {@code locale}, {@code text}. A field may be quoted with
 * {@code "} and then contain the delimiter, line breaks and {@code ""} for a literal quote — SMS texts wrap,
 * so that is the normal case rather than an edge one.
 *
 * <p>Run it by pointing the properties at the file and enabling it for that run:
 *
 * <pre>
 * commhub.import.templates.enabled=true
 * commhub.import.templates.file=/opt/commhub/templates.csv
 * commhub.import.templates.author=legacy-import
 * commhub.import.templates.approver=ivanov   # omit to import everything as drafts
 * </pre>
 *
 * <p>{@code approver} publishes the imported versions and must differ from {@code author}: maker/checker
 * applied honestly to a migration, where the import is the author and the person who ran it is the reviewer
 * (FR-4.2). Without it the whole file lands as drafts, which is the way to see what a file would do before
 * letting it decide what customers receive.
 */
package uz.hamkorbank.commhub.adapter.in.importer;
