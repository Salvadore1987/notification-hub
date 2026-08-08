package uz.hamkorbank.commhub.adapter.in.admin.support;

import java.util.List;
import java.util.function.Function;

/**
 * Renders a list into the CSV of §11.2 ("Статистика/Отчёты", "Аудит").
 *
 * <p>Hand-written rather than a library, because what this needs is one page of RFC 4180 and the two
 * details around it that a library would not have opinions about.
 *
 * <p>The first is the BOM. These files are opened in Excel on Windows, which without one reads UTF-8 as
 * the system codepage and turns every Cyrillic reason code into mojibake — the export then looks broken
 * to the only people who asked for it.
 *
 * <p>The second is the leading apostrophe on a cell that starts with {@code = + - @}. Excel treats such
 * a cell as a formula, which turns an exported field into code that runs when the file is opened. None
 * of the columns exported here should ever begin that way, which is exactly why the guard is
 * unconditional: the day one does, it will be because somebody put it in a template or a rejection
 * detail, and that is the day it matters.
 */
public final class CsvRenderer {

    /** UTF-8 byte order mark, so Excel reads the file as UTF-8. */
    public static final String BOM = "﻿";

    private static final String SEPARATOR = ",";
    private static final String LINE_END = "\r\n";
    private static final String FORMULA_PREFIXES = "=+-@";

    private CsvRenderer() {}

    /** Header row plus one row per item, in the order the list came in. */
    public static <T> String render(List<String> header, List<T> rows, Function<T, List<String>> columns) {
        StringBuilder csv = new StringBuilder(BOM);
        appendRow(csv, header);
        rows.forEach(row -> appendRow(csv, columns.apply(row)));
        return csv.toString();
    }

    private static void appendRow(StringBuilder csv, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            csv.append(escape(cells.get(i)));
        }
        csv.append(LINE_END);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String cell = defuse(value);
        if (cell.indexOf('"') < 0 && cell.indexOf(',') < 0 && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0) {
            return cell;
        }
        return '"' + cell.replace("\"", "\"\"") + '"';
    }

    private static String defuse(String value) {
        if (value.isEmpty() || FORMULA_PREFIXES.indexOf(value.charAt(0)) < 0) {
            return value;
        }
        return "'" + value;
    }
}
