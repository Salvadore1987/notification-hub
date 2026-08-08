/**
 * Domain services: logic that spans aggregates or configuration (SRS §4.2 {@code domain/service}).
 *
 * <ul>
 *   <li>{@code SegmentCalculator} — SMS encoding, billed length and segment count (MP-06, §18.3);
 *   <li>{@code Router} — channel and provider selection with balancing (MP-05, FR-2.3);
 *   <li>{@code FallbackChain} — reserve order inside a channel and across channels (FR-2.2, MP-03).
 * </ul>
 *
 * <p>All of them are stateless and free of framework and clock dependencies: configuration snapshots and
 * counters are passed in by the application layer (AR-02, AR-07). Bootstrap registers them as
 * {@code @Bean}s.
 */
package uz.hamkorbank.commhub.domain.service;
