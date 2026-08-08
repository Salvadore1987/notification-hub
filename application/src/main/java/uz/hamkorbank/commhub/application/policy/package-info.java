/**
 * Tunable policies of the pipeline: dedup window, retry/failover budget, frequency cap.
 *
 * <p>Plain immutable records with sane defaults; {@code bootstrap} binds them to configuration
 * properties, so operations can change them without touching the core (AD-07).
 */
package uz.hamkorbank.commhub.application.policy;
