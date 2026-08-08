package uz.hamkorbank.commhub.adapter.out.persistence.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uz.hamkorbank.commhub.adapter.out.persistence.AbstractPersistenceIT;
import uz.hamkorbank.commhub.application.port.out.ProviderDeliveryStats;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** Health of a provider is read off the attempts the Hub already records (FR-6.3, PR-02). */
class ProviderStatsPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-08-08T10:15:30Z");

    private static final Instant WINDOW_START = NOW.minusSeconds(300);

    private final ProviderStatsPersistenceAdapter stats;

    ProviderStatsPersistenceIT(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, ProviderStatsPersistenceAdapter stats) {
        super(jdbcClient, transactionTemplate);
        this.stats = stats;
    }

    @BeforeEach
    void clearAttempts() {
        truncate("delivery_attempt");
    }

    @Test
    @DisplayName("FR-6.3: errors and timeouts count as failures, a business rejection does not")
    void aggregatesTheWindow() {
        // Arrange
        ProviderId provider = ProviderId.newId();
        attempt(provider, AttemptResult.ACCEPTED, NOW.minusSeconds(60), 120);
        attempt(provider, AttemptResult.REJECTED, NOW.minusSeconds(50), 90);
        attempt(provider, AttemptResult.ERROR, NOW.minusSeconds(40), 300);
        attempt(provider, AttemptResult.TIMEOUT, NOW.minusSeconds(30), 10_000);

        // Act
        ProviderDeliveryStats figures = stats.statsOf(provider, WINDOW_START, NOW);

        // Assert
        assertThat(figures.attempts()).isEqualTo(4L);
        assertThat(figures.failures()).isEqualTo(2L);
        assertThat(figures.timeouts()).isEqualTo(1L);
        assertThat(figures.errorRate()).isEqualTo(0.5d);
        assertThat(figures.averageLatencyMillis()).isEqualTo((120 + 90 + 300 + 10_000) / 4.0d);
    }

    @Test
    @DisplayName("an in-flight attempt is not yet evidence of anything and is left out")
    void ignoresPendingAndOlderAttempts() {
        // Arrange
        ProviderId provider = ProviderId.newId();
        attempt(provider, AttemptResult.PENDING, NOW.minusSeconds(10), null);
        attempt(provider, AttemptResult.ERROR, NOW.minusSeconds(3_600), 500);

        // Act
        ProviderDeliveryStats figures = stats.statsOf(provider, WINDOW_START, NOW);

        // Assert
        assertThat(figures.isIdle()).isTrue();
        assertThat(figures.errorRate()).isZero();
    }

    @Test
    @DisplayName("the pass reads every provider that saw traffic, one row each")
    void groupsByProvider() {
        // Arrange
        ProviderId playmobile = ProviderId.newId();
        ProviderId smsGate = ProviderId.newId();
        attempt(playmobile, AttemptResult.ACCEPTED, NOW.minusSeconds(20), 100);
        attempt(smsGate, AttemptResult.ERROR, NOW.minusSeconds(20), 200);
        attempt(smsGate, AttemptResult.ERROR, NOW.minusSeconds(10), 200);

        // Act + Assert
        assertThat(stats.statsSince(WINDOW_START, NOW)).hasSize(2);
        assertThat(stats.statsOf(smsGate, WINDOW_START, NOW).errorRate()).isEqualTo(1.0d);
        assertThat(stats.statsOf(ProviderId.newId(), WINDOW_START, NOW).isIdle())
                .isTrue();
    }

    private void attempt(ProviderId provider, AttemptResult result, Instant requestAt, Integer latencyMs) {
        jdbc().sql("""
                        INSERT INTO delivery_attempt (id, message_id, request_at, provider_id, provider_code,
                                                      provider_channel, provider_adapter_type, attempt_no, result,
                                                      response_at, latency_ms)
                        VALUES (:id, :messageId, :requestAt, :providerId, 'PLAYMOBILE', 'SMS', 'playmobile-http',
                                1, :result, :responseAt, :latency)
                        """)
                .param("id", UuidV7.generate())
                .param("messageId", MessageId.newId().value())
                .param("requestAt", at(requestAt))
                .param("providerId", provider.value())
                .param("result", result.name())
                .param("responseAt", result == AttemptResult.PENDING ? null : at(requestAt.plusMillis(50)))
                .param("latency", latencyMs)
                .update();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
