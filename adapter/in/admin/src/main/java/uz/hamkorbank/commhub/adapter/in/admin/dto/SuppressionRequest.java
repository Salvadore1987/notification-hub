package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of adding an entry to the suppression list (§11.2 "Suppression list", FR-5.1).
 *
 * <p>The address arrives in the clear — the operator has a number in front of them, not a hash — and is
 * validated by the value object of its channel before it is hashed (DB-04). A mistyped number is
 * refused here rather than turned into a hash that matches nothing for the rest of its life.
 *
 * <p>Exactly one of {@code address} and {@code clientId} is given: banning an address and banning a
 * customer are different entries with different scopes, and a request naming both would be a request
 * whose meaning depends on which the server looked at first.
 *
 * @param channel {@code null} with a {@code clientId} bans the customer on every channel
 * @param validUntil ISO-8601 instant; {@code null} for an entry that does not expire
 */
public record SuppressionRequest(String channel, String address, String clientId, String reason, String validUntil) {}
