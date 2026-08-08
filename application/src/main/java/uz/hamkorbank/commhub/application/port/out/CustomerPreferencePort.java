package uz.hamkorbank.commhub.application.port.out;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;

/**
 * Client communication preferences: channels, language, consents (FR-8.2, FR-5.2).
 *
 * <p>Phase 2 of the programme owns the real store. In the MVP the Hub ships a stub adapter that
 * returns {@link Optional#empty()}, which the delivery filters read as "no preference recorded" and
 * therefore do not block anything; the port is already here so that switching to the real service is
 * an adapter change only (AR-04).
 */
public interface CustomerPreferencePort {

    Optional<CustomerPreferences> find(ClientId clientId);
}
