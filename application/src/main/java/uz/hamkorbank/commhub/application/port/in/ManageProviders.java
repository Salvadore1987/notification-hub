package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.port.in.command.DeleteProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStateCommand;
import uz.hamkorbank.commhub.application.port.in.command.RegisterProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateProviderCommand;

/**
 * Administration of the provider profiles (FR-2.1, FR-2.5, FR-2.6, FR-2.7).
 *
 * <p>One interface for the whole life cycle of one aggregate rather than four single-method use cases:
 * the operations share their transaction, their audit record and their invalidation of the routing
 * snapshot, and splitting them would only spread that agreement over four files.
 *
 * <p>Every change is applied to routing without a restart, within the refresh window of the
 * configuration cache (AD-07, NF-07).
 */
public interface ManageProviders {

    ProviderView register(RegisterProviderCommand command);

    ProviderView update(UpdateProviderCommand command);

    /** Enables, disables or puts a provider into maintenance (FR-2.7). */
    ProviderView changeState(ProviderStateCommand command);

    void delete(DeleteProviderCommand command);
}
