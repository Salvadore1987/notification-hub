package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * A quiet-hours window, in the shape the panel reads and writes (FR-5.3, AD-07).
 *
 * @param start local time the window opens, {@code HH:mm}
 * @param end local time it closes; a window that wraps midnight is normal and is what most of them do
 * @param zone IANA zone, {@code Asia/Tashkent} unless the deployment says otherwise
 * @param behavior {@code DEFER} or {@code REJECT}
 */
public record QuietHoursDto(String start, String end, String zone, String behavior) {}
