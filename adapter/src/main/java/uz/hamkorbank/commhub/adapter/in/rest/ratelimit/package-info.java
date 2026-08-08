/**
 * Rate limiting of the synchronous API per source stream (IR-02).
 *
 * <p>This is transport protection — how often a caller may knock — and not the business quotas of
 * FR-2.6, which count messages and cost against a stream, a channel and a provider inside the
 * pipeline. The two answer with different codes for exactly that reason: {@code RATE_LIMITED} means
 * "slow down", {@code QUOTA_EXCEEDED} means "your budget is spent".
 */
package uz.hamkorbank.commhub.adapter.in.rest.ratelimit;
