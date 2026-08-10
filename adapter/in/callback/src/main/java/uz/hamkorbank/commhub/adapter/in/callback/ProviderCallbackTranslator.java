package uz.hamkorbank.commhub.adapter.in.callback;

import java.util.List;
import java.util.Map;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;

/**
 * What one provider's delivery report means in canonical terms (ST-03, PM-02, SG-02).
 *
 * <p>The extension point of AR-04 on the inbound side: adding a provider adds a translator and nothing
 * else — no change to this package, none to the application layer, none to the domain. Playmobile
 * pushes {@code message-id/status/status-date} to {@code /status} (§18.1) and SMS Gate posts
 * {@code id/code/description} through its FEEDBACK service (§18.2); both become the same
 * {@link ProviderStatusCommand}, and the mapping tables of §18.1–18.2 live in the implementations.
 *
 * <p>Implementations must be idempotent-friendly: a provider repeats reports, so translating the same
 * payload twice has to produce the same commands.
 */
public interface ProviderCallbackTranslator {

    /** Provider whose reports this translator understands; the path variable is matched against it. */
    ProviderCode providerCode();

    /**
     * Translates one callback into the canonical reports it carries — several, because some providers
     * batch the delivery receipts of a whole send into one request (§18.2).
     *
     * @param body raw request body as the provider sent it
     * @param parameters query and form parameters, which is where the older providers put their fields
     * @throws uz.hamkorbank.commhub.adapter.in.contract.InboundContractException when the payload does
     *     not match what the provider's documentation describes
     */
    List<ProviderStatusCommand> translate(String body, Map<String, String> parameters);
}
