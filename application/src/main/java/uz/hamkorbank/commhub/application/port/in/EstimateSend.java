package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.SendEstimateView;
import uz.hamkorbank.commhub.application.port.in.query.SendEstimateQuery;

/**
 * What a panel-initiated send would cost, computed before it happens (ADR-0038, FR-4.4).
 *
 * <p>Neither {@code EvaluateRoute} nor {@code GetTemplates.preview} answers this on its own: the first
 * says which provider one hypothetical message would take, the second what one wording costs. The
 * question here is what <em>this recipient list</em> with <em>these per-row variables</em> costs on the
 * route it will actually take — so this use case asks both rather than re-implementing either.
 */
public interface EstimateSend {

    SendEstimateView estimate(SendEstimateQuery query);
}
