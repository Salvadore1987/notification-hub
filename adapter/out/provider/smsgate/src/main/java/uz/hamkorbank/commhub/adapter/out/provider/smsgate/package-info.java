/**
 * Driven adapter: SMS Gate v4.3 ({@code smsgw.vas.uz}), the reserve SMS provider (§9.2, §18.2, SG-01…SG-04).
 *
 * <p>The second implementation of {@code SmsProviderPort}, and the one that proves AR-04: it differs
 * from Playmobile in its wire format, its error table, its authentication and its idempotency
 * properties, and none of that reaches the application layer. Choosing between the two is configuration
 * in the {@code channel} fallback order (FR-2.2), not code.
 *
 * <p>The API is poorer than Playmobile's in two ways that the Hub compensates for rather than exposes
 * (SG-01): there are no provider-side templates, so the text arrives already rendered; and there is no
 * send window, so a deferred message waits in the Hub and is dispatched when its window opens.
 *
 * <p>Three consequences of the API are visible in this package and are deliberate:
 *
 * <ul>
 *   <li>no retry inside a delivery attempt — {@code /api/v2/send} carries no client-supplied id, so a
 *       retry cannot be deduplicated and would be a second SMS to a customer;
 *   <li>credentials travel in every request body, so a request body is never logged here and
 *       {@code SmsGateCredentials} does not render itself (SG-04);
 *   <li>delivery reports can be lost, so {@code SmsGateReconciler} polls {@code /api/v2/search} for
 *       messages that were accepted and never reported on (SG-03).
 * </ul>
 */
package uz.hamkorbank.commhub.adapter.out.provider.smsgate;
