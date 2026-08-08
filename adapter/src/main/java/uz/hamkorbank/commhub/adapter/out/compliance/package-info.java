/**
 * Everything the compliance filters need from the outside world (FR-5.1…FR-5.4, SEC-05).
 *
 * <p>Two things live here and they are related by subject rather than by mechanism: the deployment settings of
 * the filters ({@code ComplianceProperties} → {@code FrequencyCapPolicy}, {@code PanPolicy}) and the stub
 * behind {@code CustomerPreferencePort}, which is what the SRS asks the MVP to ship for consents (FR-8.2).
 *
 * <p>Not here: the suppression list and the frequency counters, which are storage and live in
 * {@code out/persistence/delivery}; and quiet hours, which are configuration of a stream or a channel in the
 * database and are applied without a restart (FR-5.3, AD-07). Only the settings that are the same for the
 * whole installation stay in the yaml — a cap that could be edited per stream at runtime would be one more
 * thing to reconcile between the registry and the deployment.
 */
package uz.hamkorbank.commhub.adapter.out.compliance;
