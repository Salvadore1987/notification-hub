/**
 * Driven adapter: Firebase Cloud Messaging HTTP v1 (§9.4.1, PU-01…PU-05).
 *
 * <p>The Android half of the push channel, and optionally the iOS half as well (PU-05). One more
 * implementation of a channel output port, chosen by configuration like every other provider (AR-04);
 * what is genuinely different about push, and therefore not in this package, is that one message has
 * several addresses — that lives in the application layer's fan-out (PU-09).
 *
 * <p>Three properties of the API shape this package:
 *
 * <ul>
 *   <li><b>One message per request.</b> The legacy batch endpoint is gone, so throughput is
 *       concurrency: hundreds of blocking calls on virtual threads (PU-02, AR-07). Nothing here needs
 *       to know that — the adapter sends one notification and returns.
 *   <li><b>Authentication expires.</b> The service account key is exchanged for an access token valid
 *       for an hour ({@link uz.hamkorbank.commhub.adapter.out.provider.fcm.FcmAccessTokens}), refreshed
 *       ahead of expiry so the renewal happens between messages rather than during one (PU-01).
 *   <li><b>A refusal is usually about the token.</b> {@code UNREGISTERED} means an uninstalled
 *       application, not an unwell provider, so it is returned as a rejection that retires the address
 *       rather than thrown at the circuit breaker — the same rule the SMTP adapter follows for a
 *       {@code 550} (PR-01, PU-04, EM-02).
 * </ul>
 *
 * <p>Legacy HTTP ({@code /fcm/send}) is deliberately not implemented: PU-01 names HTTP v1, and Google
 * withdrew the legacy API. The server key it authenticated with is also a single long-lived secret,
 * which is the thing SEC-04 exists to avoid.
 */
package uz.hamkorbank.commhub.adapter.out.provider.fcm;
