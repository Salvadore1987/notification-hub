package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * One entry of {@code recipient.pushTokens} (IK-03, §9.4).
 *
 * @param platform {@code IOS}, {@code ANDROID} or {@code WEB} — decides which push adapter is used
 */
public record PushTokenPayload(String platform, String token) {}
