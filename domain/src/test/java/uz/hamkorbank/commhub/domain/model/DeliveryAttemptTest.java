package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.NOW;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** Delivery attempt: provider outcome, latency and error classification (PR-01, PR-03, §18.1). */
class DeliveryAttemptTest {

    private static final MessageId MESSAGE_ID = MessageId.newId();
    private static final ProviderRef PLAYMOBILE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("PLAYMOBILE"), Channel.SMS, AdapterType.of("playmobile-http"));

    @Test
    @DisplayName("a started attempt is pending and carries the Hub-generated provider message id (§9.1)")
    void startedAttemptIsPending() {
        // Act
        DeliveryAttempt attempt = start(ProviderMessageId.of("HB0000000001"));

        // Assert
        assertThat(attempt.result()).isEqualTo(AttemptResult.PENDING);
        assertThat(attempt.isComplete()).isFalse();
        assertThat(attempt.isAccepted()).isFalse();
        assertThat(attempt.providerMessageId()).contains(ProviderMessageId.of("HB0000000001"));
        assertThat(attempt.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(attempt.provider()).isEqualTo(PLAYMOBILE);
        assertThat(attempt.attemptNumber()).isEqualTo(1);
        assertThat(attempt.requestAt()).isEqualTo(NOW);
        assertThat(attempt.latency()).isEmpty();
        assertThat(attempt.responseAt()).isEmpty();
        assertThat(attempt.errorClass()).isEmpty();
    }

    @Test
    @DisplayName("§9.2: the provider-assigned id is stored when the attempt succeeds")
    void successStoresTheProviderId() {
        // Arrange
        DeliveryAttempt attempt = start(null);

        // Act
        attempt.succeed("0", ProviderMessageId.of("452136"), NOW.plusMillis(320));

        // Assert
        assertThat(attempt.result()).isEqualTo(AttemptResult.ACCEPTED);
        assertThat(attempt.isAccepted()).isTrue();
        assertThat(attempt.providerMessageId()).contains(ProviderMessageId.of("452136"));
        assertThat(attempt.responseCode()).contains("0");
        assertThat(attempt.latency()).contains(Duration.ofMillis(320));
        assertThat(attempt.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("§18.1: a rejection is non-retryable, a 5xx is retryable, code 102 is blocking")
    void errorClassificationDrivesRetries() {
        // Arrange
        DeliveryAttempt rejected = start(null);
        DeliveryAttempt failed = start(null);
        DeliveryAttempt blocked = start(null);

        // Act
        rejected.reject("202", "Empty recipient", NOW.plusMillis(10));
        failed.fail("100", ErrorClass.RETRYABLE, "Internal server error", NOW.plusMillis(10));
        blocked.fail("102", ErrorClass.BLOCKING, "Account lock", NOW.plusMillis(10));

        // Assert
        assertThat(rejected.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(rejected.errorClass()).contains(ErrorClass.NON_RETRYABLE);
        assertThat(rejected.errorDescription()).contains("Empty recipient");
        assertThat(failed.isRetryable()).isTrue();
        assertThat(blocked.isBlockingForProvider()).isTrue();
    }

    @Test
    @DisplayName("PR-01: a timeout is retryable")
    void timeoutIsRetryable() {
        // Arrange
        DeliveryAttempt attempt = start(null);

        // Act
        attempt.timeout(NOW.plusSeconds(10));

        // Assert
        assertThat(attempt.result()).isEqualTo(AttemptResult.TIMEOUT);
        assertThat(attempt.isRetryable()).isTrue();
        assertThat(attempt.latency()).contains(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an attempt can only be completed once and not before it started")
    void completionIsGuarded() {
        // Arrange
        DeliveryAttempt attempt = start(null);
        attempt.succeed("0", null, NOW.plusMillis(50));

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> attempt.timeout(NOW.plusSeconds(1)))
                .withMessageContaining("already complete");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> start(null).succeed("0", null, NOW.minusSeconds(1)))
                .withMessageContaining("must not be before requestAt");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> start(null).fail("0", null, "boom", NOW));
    }

    @Test
    @DisplayName("an attempt number must be positive and the message and provider are required")
    void invariantsAreEnforced() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> DeliveryAttempt.start(AttemptId.newId(), MESSAGE_ID, PLAYMOBILE, 0, null, NOW));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> DeliveryAttempt.start(AttemptId.newId(), null, PLAYMOBILE, 1, null, NOW));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> DeliveryAttempt.start(AttemptId.newId(), MESSAGE_ID, PLAYMOBILE, 1, null, null));
    }

    private static DeliveryAttempt start(ProviderMessageId providerMessageId) {
        return DeliveryAttempt.start(AttemptId.newId(), MESSAGE_ID, PLAYMOBILE, 1, providerMessageId, NOW);
    }
}
