package uz.hamkorbank.commhub.application.port.out;

import java.util.List;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;

/**
 * Reserved port for campaigns submitted without a recipient list (FR-8.11, §18.5).
 *
 * <p><strong>Deliberately without an implementation.</strong> The Bank deferred audience resolution
 * on 06.08.2026: bulk sends go through the normal batch mechanism with recipient lists supplied by
 * the source system (FR-1.6, PU-10). The port exists so that adding audience resolution later stays a
 * pure adapter change (AR-04); no use case may depend on it in the current scope.
 */
public interface AudienceResolverPort {

    /** Resolves the recipients of a campaign; no adapter implements this in the current scope. */
    List<Recipient> resolve(AudienceQuery query);
}
