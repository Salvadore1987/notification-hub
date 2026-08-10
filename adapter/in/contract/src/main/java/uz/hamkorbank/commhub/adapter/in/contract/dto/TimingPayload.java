package uz.hamkorbank.commhub.adapter.in.contract.dto;

/**
 * {@code timing} block of IK-03 (FR-3.4, FR-8.5).
 *
 * @param sendAfter ISO-8601 instant before which the message is not handed to a provider
 * @param sendBefore ISO-8601 instant after which the message is expired instead of sent (FR-3.4)
 * @param ttlSeconds lifetime counted from acceptance; the earlier of it and {@code sendBefore} wins
 * @param allowedWindow daily window the send has to fall into
 * @param localTime whether the window is read in the recipient's local time (Asia/Tashkent, FR-5.3)
 */
public record TimingPayload(
        String sendAfter,
        String sendBefore,
        Long ttlSeconds,
        AllowedWindowPayload allowedWindow,
        Boolean sendEvenly,
        Boolean localTime) {}
