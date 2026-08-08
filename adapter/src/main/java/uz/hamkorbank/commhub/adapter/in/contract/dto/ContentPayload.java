package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * {@code content} block of IK-03: ready-made content per channel (MP-02).
 *
 * <p>All three may be absent when the message references a template instead (FR-4.3); all three may be
 * present when the source system wants the wording to differ per channel.
 */
public record ContentPayload(SmsContentPayload sms, EmailContentPayload email, PushContentPayload push) {}
