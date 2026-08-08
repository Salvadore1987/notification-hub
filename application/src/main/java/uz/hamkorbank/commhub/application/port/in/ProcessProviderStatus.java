package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;

/**
 * Applies a provider delivery report to a message (AD-06, ST-01, ST-03).
 *
 * <p>Driven by the provider callback adapters (Playmobile DLR push, SMS Gate FEEDBACK) and by the
 * reconciliation job (SG-03). Idempotent: providers repeat their callbacks, so a report that changes
 * nothing is answered successfully (PM-02).
 */
public interface ProcessProviderStatus {

    ProcessProviderStatusResult process(ProviderStatusCommand command);
}
