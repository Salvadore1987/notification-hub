package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * {@code timing.allowedWindow} of IK-03: the daily hours a send may happen in (FR-8.5).
 *
 * @param start local time in {@code HH:mm} or {@code HH:mm:ss}
 * @param end local time in {@code HH:mm} or {@code HH:mm:ss}
 */
public record AllowedWindowPayload(String start, String end) {}
