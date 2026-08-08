package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.StreamControlResult;
import uz.hamkorbank.commhub.application.port.in.command.StreamActionCommand;

/**
 * Suspends an inbound stream (FR-3.2, FR-1.3).
 *
 * <p>New submissions are rejected with {@code STREAM_SUSPENDED} (IR-01) and accepted messages of the
 * stream stop being dispatched, except {@code CRITICAL_OTP} (FR-3.2).
 */
public interface SuspendStream {

    StreamControlResult suspend(StreamActionCommand command);
}
