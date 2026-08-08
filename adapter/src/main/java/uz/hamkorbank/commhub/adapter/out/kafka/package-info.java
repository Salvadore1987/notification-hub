/**
 * Driven adapter over Kafka: the outbound side of the Hub (§8.1 IK-02, AD-03).
 *
 * <p>Holds the producer, the wire format of §6.4 and the {@code StatusPublisherPort} implementation the
 * outbox relay publishes through. Nothing here decides <em>what</em> to publish or <em>when</em> — the
 * relay use case does, and it is the only caller (AD-03: a use case never publishes directly).
 *
 * <p>The published contract is JSON, one document per event, keyed by {@code messageId} so the statuses
 * of one message keep their order on the topic. The schema is a resource under {@code schema/} and is
 * registered in the Schema Registry with BACKWARD compatibility by operations (NF-08); the serializer
 * itself does not talk to the registry, which keeps the registry off the sending path.
 *
 * <p>Inbound consumers (§8.1 IK-01) are a driving concern and land in {@code adapter/in/kafka} in
 * Phase 6.
 */
package uz.hamkorbank.commhub.adapter.out.kafka;
