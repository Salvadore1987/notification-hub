package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * {@code content.sms} of IK-03.
 *
 * @param originator sender name; absent takes the default of the provider configuration (FR-2.4)
 */
public record SmsContentPayload(String text, String originator) {}
