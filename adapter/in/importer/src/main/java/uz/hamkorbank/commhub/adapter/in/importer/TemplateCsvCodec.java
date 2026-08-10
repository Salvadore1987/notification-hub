package uz.hamkorbank.commhub.adapter.in.importer;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.TemplateImportResult;
import uz.hamkorbank.commhub.application.port.in.command.ImportTemplatesCommand;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;

/**
 * Reads the Bank's template export into import rows (FR-4.6).
 *
 * <p>Columns are addressed by the names in the header line, not by position: the file is produced by hand
 * from an Excel sheet, and the one thing such a file reliably does is grow a column in the middle. Required
 * columns are {@code code}, {@code channel}, {@code locale}, {@code text}; {@code subject}, {@code html},
 * {@code direction} and {@code owner} are optional and unknown columns are ignored, so the Bank's own
 * bookkeeping columns can stay in the sheet. {@code html} is the HTML alternative of an email body and is
 * refused on any other channel (EM-01).
 *
 * <p>Quoting is RFC 4180: a field may be wrapped in {@code "} and then contain the delimiter, line breaks and
 * {@code ""} for a literal quote. SMS texts wrap, so multi-line fields are not an edge case here.
 *
 * <p>A row whose channel or locale is not one the Hub knows is reported as a failure and the rest of the
 * file still goes in, matching how the use case treats a row the domain refuses: a 470-row file converted by
 * hand will have a few bad rows, and finding them one run at a time is the slowest possible way to migrate.
 * A broken <em>file</em> — no header, a required column missing — is a different thing and stops everything.
 */
@Component
public class TemplateCsvCodec {

    private static final String CODE = "code";
    private static final String CHANNEL = "channel";
    private static final String LOCALE = "locale";
    private static final String SUBJECT = "subject";
    private static final String TEXT = "text";
    private static final String HTML = "html";
    private static final String DIRECTION = "direction";
    private static final String OWNER = "owner";
    private static final List<String> REQUIRED = List.of(CODE, CHANNEL, LOCALE, TEXT);

    public Parsed parse(Reader reader, char delimiter) throws IOException {
        List<List<String>> records = records(reader, delimiter);
        if (records.isEmpty()) {
            throw new TemplateImportException("the template file is empty");
        }
        Map<String, Integer> columns = header(records.getFirst());
        List<ImportTemplatesCommand.Row> rows = new ArrayList<>();
        List<TemplateImportResult.Failure> failures = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            readRow(records.get(index), columns, index, rows, failures);
        }
        if (rows.isEmpty() && failures.isEmpty()) {
            throw new TemplateImportException("the template file has a header but no rows");
        }
        return new Parsed(rows, failures);
    }

    private static void readRow(
            List<String> record,
            Map<String, Integer> columns,
            int row,
            List<ImportTemplatesCommand.Row> rows,
            List<TemplateImportResult.Failure> failures) {
        if (isBlank(record)) {
            return;
        }
        String code = value(record, columns, CODE);
        String locale = value(record, columns, LOCALE);
        try {
            rows.add(new ImportTemplatesCommand.Row(
                    code,
                    enumValue(Channel.class, value(record, columns, CHANNEL), CHANNEL),
                    enumValue(ContentLocale.class, locale, LOCALE),
                    value(record, columns, SUBJECT),
                    value(record, columns, TEXT),
                    value(record, columns, HTML),
                    value(record, columns, DIRECTION),
                    value(record, columns, OWNER)));
        } catch (RuntimeException e) {
            failures.add(new TemplateImportResult.Failure(code, locale, "row %d: %s".formatted(row, e.getMessage())));
        }
    }

    /** Column name → position; a missing required column fails the whole file rather than a row. */
    private static Map<String, Integer> header(List<String> record) {
        try {
            return CsvRecords.header(record, REQUIRED);
        } catch (IllegalArgumentException e) {
            throw new TemplateImportException("the template " + e.getMessage(), e);
        }
    }

    private static String value(List<String> record, Map<String, Integer> columns, String column) {
        return CsvRecords.value(record, columns, column);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String column) {
        if (value == null) {
            throw new TemplateImportException("%s is empty".formatted(column));
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TemplateImportException("unknown %s '%s'".formatted(column, value));
        }
    }

    private static boolean isBlank(List<String> record) {
        return CsvRecords.isBlank(record);
    }

    private static List<List<String>> records(Reader reader, char delimiter) throws IOException {
        try {
            return CsvRecords.read(reader, delimiter);
        } catch (IllegalArgumentException e) {
            throw new TemplateImportException("the template " + e.getMessage(), e);
        }
    }

    /**
     * What one file yielded (FR-4.6).
     *
     * @param rows rows the use case can accept
     * @param failures rows the file itself got wrong, with their line numbers
     */
    public record Parsed(List<ImportTemplatesCommand.Row> rows, List<TemplateImportResult.Failure> failures) {

        public Parsed {
            rows = List.copyOf(rows);
            failures = List.copyOf(failures);
        }
    }
}
