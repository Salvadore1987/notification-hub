package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Answer to "may the Hub still write to this address" (§11.2 "Suppression list", FR-5.1).
 *
 * @param entry the row that suppresses it, when there is one; {@code null} means nothing is in the way
 */
public record SuppressionCheckResponse(String channel, boolean suppressed, SuppressionResponse entry) {}
