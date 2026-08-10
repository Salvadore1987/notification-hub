/**
 * Driving adapter: the synchronous API of the source systems, {@code /api/v1} (§8.2).
 *
 * <p>Controllers translate and nothing else (AR-06). What arrives is the shared inbound contract of
 * {@code adapter.in.contract}; what leaves is a response record of {@code dto} built by the MapStruct
 * mapper of {@code mapper}; what goes wrong becomes a {@code problem+json} of {@code problem}, rendered
 * by one of the advices in {@code handlers}.
 *
 * <p>Authentication (an OAuth2 client-credentials token, §8.2) is applied by the chain in
 * {@code bootstrap} and switched on per contour by {@code commhub.security.require-source-system-token}
 * — unlike the admin panel, which is closed unconditionally (ADR-0037), because the callers of this API
 * are inside the Bank's contour and a local stack has to be able to submit a message without minting a
 * token first. Where the entitlement matters the adapter checks it itself: {@code StreamAccessGuard}
 * answers SEC-01. What the adapter owns regardless is the caller-facing protection of IR-02: a
 * per-stream rate limit that answers 429 with {@code Retry-After}.
 *
 * <p>The published contract is {@code resources/openapi/comm-hub-api-v1.yaml} (IR-03); a test walks
 * the controller mappings and fails if the document has drifted from them.
 */
package uz.hamkorbank.commhub.adapter.in.rest;
