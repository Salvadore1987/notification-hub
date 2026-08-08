/**
 * Driving adapters that are triggered by time rather than by a caller (Phase 5).
 *
 * <p>A scheduler belongs on the driving side even though nothing arrives from outside: it calls input
 * ports, exactly as the REST and Kafka adapters of Phase 6 do, and holds no logic of its own beyond how
 * often to call and what to log. Jobs that only maintain the storage — partitions, retention — stay
 * next to that storage in {@code out/persistence/support} instead; they drive no use case.
 */
package uz.hamkorbank.commhub.adapter.in.scheduler;
