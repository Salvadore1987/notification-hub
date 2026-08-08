package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.StreamView;
import uz.hamkorbank.commhub.application.port.in.command.RegisterStreamCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateStreamCommand;

/**
 * Administration of the source systems: defaults, quotas, quiet hours, request limits (FR-1.3,
 * FR-2.4, FR-2.6, FR-5.3, IR-02, TC-02).
 *
 * <p>Suspension and resumption live in {@code SuspendStream}/{@code ResumeStream}: those are incident
 * operations an operator performs on a running stream, and they belong next to the batch controls
 * rather than in the configuration editor (FR-3.2).
 *
 * <p>There is no deletion. A stream owns messages, statistics and quota counters that outlive it by
 * years (DB-03); a source system that stops sending is disabled, not erased.
 */
public interface ManageStreams {

    StreamView register(RegisterStreamCommand command);

    StreamView update(UpdateStreamCommand command);
}
