package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.ConnectionStatus;
import uz.hamkorbank.commhub.domain.model.type.StreamStatus;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A registered source system for the administration screens (FR-1.3, FR-2.4, FR-2.6, §18.4).
 *
 * @param connectionStatus liveness derived from the last activity, not a stored field (FR-1.3)
 */
public record StreamView(
        StreamId streamId,
        String name,
        StreamStatus status,
        ConnectionStatus connectionStatus,
        Stream.Defaults defaults,
        Limits limits,
        Instant lastActivityAt) {

    public StreamView {
        Guard.notNull(streamId, "StreamView.streamId");
        Guard.notBlank(name, "StreamView.name");
        Guard.notNull(status, "StreamView.status");
        Guard.notNull(connectionStatus, "StreamView.connectionStatus");
        Guard.notNull(defaults, "StreamView.defaults");
        Guard.notNull(limits, "StreamView.limits");
    }

    public Optional<Instant> lastActivityAtOptional() {
        return Optional.ofNullable(lastActivityAt);
    }

    /**
     * What the stream is allowed to send and when (FR-2.6, FR-5.3, IR-02).
     *
     * @param rateLimit request rate of the synchronous API; unlimited means the deployment default
     */
    public record Limits(QuotaConfig quota, RateLimit rateLimit, QuietHours quietHours) {

        public Limits {
            Guard.notNull(quota, "Limits.quota");
            Guard.notNull(rateLimit, "Limits.rateLimit");
        }

        public Optional<QuietHours> quietHoursOptional() {
            return Optional.ofNullable(quietHours);
        }
    }
}
