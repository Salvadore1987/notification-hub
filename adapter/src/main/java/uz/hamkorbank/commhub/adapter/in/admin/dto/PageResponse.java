package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * One page of a server-paged list (UI-03).
 *
 * <p>Offset paging rather than a cursor, and deliberately: these screens are read by a person who jumps
 * to page seven, sorts by a column and comes back — the things a cursor is bad at. The rows underneath
 * do move while they page, which for a list of what happened yesterday is not a correctness problem.
 * The one place that could not tolerate it is the export, and the export does not page — it walks the
 * same query to the end.
 *
 * @param total number of matching rows, which is what lets the client draw the pager at all
 */
public record PageResponse<T>(List<T> items, long total, int limit, int offset) {

    public static <T> PageResponse<T> of(List<T> items, long total, int limit, int offset) {
        return new PageResponse<>(List.copyOf(items), total, limit, offset);
    }
}
