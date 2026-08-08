package uz.hamkorbank.commhub.application.service.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Rotation counter behind round-robin and weighted balancing (FR-2.3).
 *
 * <p>The {@code Router} is stateless by design and receives the position from outside, which is what
 * lets one router instance serve every virtual thread (AR-07). The counter is per instance and per
 * channel: with several Hub instances each rotates independently, which still distributes the load
 * evenly over a provider set — exact global ordering is not a requirement of FR-2.3.
 */
@Component
public class RoutingRotation {

    private final Map<Channel, AtomicLong> counters = new ConcurrentHashMap<>();

    /** Next position for the channel; always non-negative. */
    public long next(Channel channel) {
        Guard.notNull(channel, "channel");
        return counters.computeIfAbsent(channel, key -> new AtomicLong()).getAndIncrement() & Long.MAX_VALUE;
    }
}
