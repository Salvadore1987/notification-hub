package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.ExpireMessagesResult;
import uz.hamkorbank.commhub.application.port.in.command.ExpireMessagesCommand;

/**
 * Moves messages whose TTL or send window has elapsed to {@code EXPIRED} (FR-3.4).
 *
 * <p>Critical for OTP: an expired code must never reach the client. The sweep runs on a schedule and
 * the sending saga re-checks the deadline before every provider call, so a message can expire between
 * two sweeps without being sent.
 */
public interface ExpireMessages {

    ExpireMessagesResult expire(ExpireMessagesCommand command);
}
