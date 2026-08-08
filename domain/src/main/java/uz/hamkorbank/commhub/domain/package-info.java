/**
 * Domain layer: pure Java model and domain services of the Notification Hub.
 *
 * <p>Allowed dependencies: JDK, {@code java.time} and own value objects only. No Spring, JPA, Kafka
 * or Jackson (AR-02). Enforced by ArchUnit (AR-03, QA-02).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code model} — Message, Batch, Stream, Provider, Template, DeliveryAttempt, ...
 *   <li>{@code service} — Router, FallbackChain, SegmentCalculator
 * </ul>
 */
package uz.hamkorbank.commhub.domain;
