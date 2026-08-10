package uz.hamkorbank.commhub.adapter.in.admin.dto;

import java.util.List;

/**
 * Body of a bulk retry or archive over the DLQ (§11.2 "DLQ", FR-3.3).
 *
 * <p>An explicit list of message ids and never a filter. "Retry everything matching this" is one
 * mistyped field away from resending a day of traffic to customers who already got it; what the screen
 * does is show a filtered page and let somebody select from it.
 */
public record DlqActionRequest(List<String> messageIds) {}
