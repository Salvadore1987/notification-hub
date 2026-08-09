package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * Outcome of a bulk retry or archive over the DLQ (FR-3.3, §11.2 "DLQ").
 *
 * <p>Answers 200 even when some entries were skipped, and lists them: an operator selects rows from a
 * filtered list that keeps moving underneath, and failing the whole request because one row was already
 * retried would cost them the other forty-nine.
 */
public record DlqActionResponse(List<String> applied, List<String> skipped) {}
