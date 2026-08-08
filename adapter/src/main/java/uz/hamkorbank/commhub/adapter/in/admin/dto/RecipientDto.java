package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Who a dry run or a test send is aimed at (§11.2 "Маршрутизация", "Каналы и провайдеры").
 *
 * <p>A recipient rather than a channel address, exactly as the {@code Message} envelope models it
 * (MP-02): the same person is a number, a mailbox and a set of devices, and which of those a send
 * actually uses is the routing decision being tested.
 *
 * @param pushPlatform {@code APNS} or {@code FCM}; required with a token, because a token is opaque
 *     and nothing about it says which platform issued it
 */
public record RecipientDto(String clientId, String msisdn, String email, String pushToken, String pushPlatform) {}
