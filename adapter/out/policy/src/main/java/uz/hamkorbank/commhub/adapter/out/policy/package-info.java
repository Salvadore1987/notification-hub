/**
 * Deployment settings turned into the policy records the core reads (FR-1.5, PR-01, FR-6.3).
 *
 * <p>The policies themselves are plain records in {@code application/policy} — a retry budget, a dedup
 * window, a set of health thresholds. They carry no configuration mechanism on purpose: the pipeline
 * must not know that its numbers came from a yaml file (AR-03), so somebody on this side of the port has
 * to build them, exactly as {@code adapter/out/compliance} does for the filters of FR-5.4 and SEC-05.
 *
 * <p>Every one of them has a {@code defaults()} the record itself declares, and that is what an absent
 * setting resolves to. The knobs exist because the numbers they hold are operational: a retry budget is
 * changed after an incident, and rebuilding an image is not how that conversation should end.
 */
package uz.hamkorbank.commhub.adapter.out.policy;
