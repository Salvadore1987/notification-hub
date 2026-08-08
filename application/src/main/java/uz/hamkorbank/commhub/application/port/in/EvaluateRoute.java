package uz.hamkorbank.commhub.application.port.in;

import uz.hamkorbank.commhub.application.dto.RouteEvaluationView;
import uz.hamkorbank.commhub.application.port.in.query.RouteEvaluationQuery;

/**
 * Dry run of the routing configuration: which route a message would get (FR-8.9 groundwork, §11.2).
 *
 * <p>Read-only in the strict sense — nothing is stored, nothing is sent, no quota or dedup key is
 * consumed. It exists so that an operator can verify a policy edit before the Bank's traffic does it
 * for them.
 */
public interface EvaluateRoute {

    RouteEvaluationView evaluate(RouteEvaluationQuery query);
}
