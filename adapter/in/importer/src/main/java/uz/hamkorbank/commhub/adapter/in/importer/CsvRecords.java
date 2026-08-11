package uz.hamkorbank.commhub.adapter.in.importer;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading an operator-supplied CSV file (RFC 4180).
 *
 * <p>Written out rather than pulled in as a dependency: the whole of the format these files need is "a
 * quote starts a literal, two quotes are one quote". It lives here, shared by the template import of
 * FR-4.6 and the suppression import of §11.2, because two hand-written CSV readers in one codebase
 * eventually disagree about a quoted field containing a newline — and SMS texts wrap, so that is not an
 * edge case.
 *
 * <p>Columns are addressed by header name and never by position. These files are produced by hand from
 * a spreadsheet, and the one thing such a file reliably does is grow a column in the middle.
 *
 * <p>Failures here are {@link IllegalArgumentException}: what a malformed file <em>means</em> differs by
 * caller — a rejected upload, a runner that refuses to start — so each wraps it in its own type.
 */
public final class CsvRecords {

    private static final char QUOTE = '"';

    /** Byte-order mark Excel puts in front of a UTF-8 file; it is not part of the first column name. */
    private static final char BOM = '﻿';

    private CsvRecords() {}

    /** Splits the whole file into records, honouring quoted fields. */
    public static List<List<String>> read(Reader reader, char delimiter) throws IOException {
        String content = readAll(reader);
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int index = 0;
        while (index < content.length()) {
            char character = content.charAt(index++);
            if (quoted) {
                if (character != QUOTE) {
                    field.append(character);
                } else if (index < content.length() && content.charAt(index) == QUOTE) {
                    field.append(QUOTE);
                    index++;
                } else {
                    quoted = false;
                }
                continue;
            }
            if (character == QUOTE && field.isEmpty()) {
                quoted = true;
            } else if (character == delimiter) {
                record.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index < content.length() && content.charAt(index) == '\n') {
                    index++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("the file ends inside a quoted field");
        }
        if (!field.isEmpty() || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    /** Column name → position, lower-cased and trimmed; a missing required column fails the file. */
    public static Map<String, Integer> header(List<String> record, List<String> required) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < record.size(); index++) {
            String name = record.get(index).trim();
            if (!name.isEmpty() && name.charAt(0) == BOM) {
                name = name.substring(1);
            }
            columns.putIfAbsent(name.toLowerCase(Locale.ROOT), index);
        }
        List<String> missing =
                required.stream().filter(column -> !columns.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("the file has no %s column(s); header was %s"
                    .formatted(String.join(", ", missing), columns.keySet()));
        }
        return columns;
    }

    /**
     * Column name → position with the case of the file preserved (ADR-0038).
     *
     * <p>{@link #header(List, List)} lower-cases, which is right when the names are a fixed vocabulary.
     * A recipient list is the other case: every column that is not reserved is a merge variable, and
     * {@code {NAME}} stops resolving the moment its header becomes {@code name}.
     */
    public static Map<String, Integer> headerAsGiven(List<String> record) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < record.size(); index++) {
            String name = record.get(index).trim();
            if (!name.isEmpty() && name.charAt(0) == BOM) {
                name = name.substring(1);
            }
            if (!name.isEmpty()) {
                columns.putIfAbsent(name, index);
            }
        }
        return columns;
    }

    /** One cell, trimmed; {@code null} when the column is absent or the cell is empty. */
    public static String value(List<String> record, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= record.size()) {
            return null;
        }
        String value = record.get(index).trim();
        return value.isEmpty() ? null : value;
    }

    public static boolean isBlank(List<String> record) {
        return record.stream().allMatch(field -> field == null || field.isBlank());
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder content = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            content.append(buffer, 0, read);
        }
        return content.toString();
    }
}
