package uz.hamkorbank.commhub.adapter.in.contract.dto;

import java.util.List;

/**
 * {@code recipient} block of IK-03.
 *
 * <p>Every address is optional on the wire and at least one of them — or a {@code clientId} the
 * preference service can resolve later (FR-8.2) — has to be there; the rule itself lives in the
 * {@code Recipient} value object, not here.
 */
public record RecipientPayload(String clientId, String msisdn, String email, List<PushTokenPayload> pushTokens) {}
