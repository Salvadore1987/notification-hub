/**
 * Driving adapter: the synchronous API of the source systems, {@code /api/v1} (§8.2).
 *
 * <p>Controllers translate and nothing else (AR-06). What arrives is the shared inbound contract of
 * {@code adapter.in.contract}; what leaves is a response record of {@code dto} built by the MapStruct
 * mapper of {@code mapper}; what goes wrong becomes a {@code problem+json} of {@code problem}, rendered
 * by one of the advices in {@code handlers}.
 *
 * <p>Authentication (mTLS / OAuth2 client credentials, §8.2) is not wired here yet — it arrives with
 * the security phase, and until then the callers of this API are inside the Bank's contour. What the
 * adapter does own already is the caller-facing protection of IR-02: a per-stream rate limit that
 * answers 429 with {@code Retry-After}.
 *
 * <p>The published contract is {@code resources/openapi/comm-hub-api-v1.yaml} (IR-03); a test walks
 * the controller mappings and fails if the document has drifted from them.
 */
package uz.hamkorbank.commhub.adapter.in.rest;
