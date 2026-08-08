package uz.hamkorbank.commhub.bootstrap.health;

import java.util.List;
import org.apache.kafka.common.PartitionInfo;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.kafka.KafkaOutboundProperties;

/**
 * Whether the broker is reachable (NF-05, OBS-04).
 *
 * <p>Reads the producer's cached metadata for the status topic, which costs nothing while the cluster is
 * healthy and fails within {@code max.block.ms} when it is not — that bound is why the producer sets it
 * explicitly instead of leaving it at a minute.
 *
 * <p><strong>Deliberately not part of the readiness group.</strong> An instance whose broker is down can
 * still accept messages: the submission is committed with its outbox row, and the relay publishes when
 * the broker returns (AD-03). Taking the pod out of the load balancer would turn a broker outage into an
 * ingest outage, which is exactly what the outbox exists to prevent. It is a signal for the alert of
 * OBS-04, not a reason to stop serving.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaOutboundProperties outbound;

    public KafkaHealthIndicator(KafkaTemplate<String, String> kafkaTemplate, KafkaOutboundProperties outbound) {
        this.kafkaTemplate = kafkaTemplate;
        this.outbound = outbound;
    }

    @Override
    public Health health() {
        try {
            List<PartitionInfo> partitions = kafkaTemplate.partitionsFor(outbound.statusTopic());
            return Health.up()
                    .withDetail("topic", outbound.statusTopic())
                    .withDetail("partitions", partitions == null ? 0 : partitions.size())
                    .build();
        } catch (RuntimeException e) {
            return Health.down()
                    .withDetail("topic", outbound.statusTopic())
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
