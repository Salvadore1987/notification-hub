package uz.hamkorbank.commhub.adapter.out.compliance;

import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferencePort;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferences;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;

/**
 * The MVP implementation of {@link CustomerPreferencePort}: no preferences are recorded (FR-8.2, FR-5.2).
 *
 * <p>Deliberately a stub, and the SRS says so: the master system of client consents is still to be chosen
 * (§7.8 FR-8.2, open question 8 of §17), and this Hub is a transport layer — it must not become the place
 * where the Bank's consent register is invented, because a second register of consents is worse than none.
 *
 * <p>"No record" is read by the delivery filters as "no restriction", so nothing is blocked by this answer.
 * The opt-outs the Hub does enforce today are the ones written into its own suppression list with reason
 * {@code OPT_OUT} (FR-5.1) — an operator or a source system records them and the pipeline checks them before
 * every send. When the master system arrives, it is this class that is replaced and nothing else (AR-04).
 */
@Component
public class StubCustomerPreferenceAdapter implements CustomerPreferencePort {

    @Override
    public Optional<CustomerPreferences> find(ClientId clientId) {
        return Optional.empty();
    }
}
