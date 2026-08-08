package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.ProviderHealthResult;
import uz.hamkorbank.commhub.application.port.in.command.CheckProviderHealthCommand;

/**
 * Proactive detection of provider degradation with automatic failover and failback (FR-6.3, PR-02).
 *
 * <p>Driven by a scheduler, not by the sending path: a message must never wait for a health decision,
 * and a decision must not be taken from the single attempt that happens to be in flight.
 */
public interface CheckProviderHealth {

    ProviderHealthResult check(CheckProviderHealthCommand command);
}
