package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateProperties.Sending;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/** The response and status tables of §18.2 (SG-01, SG-02). */
class SmsGateCatalogsTest {

    @ParameterizedTest
    @ValueSource(strings = {"10", "11", "12", "13"})
    @DisplayName("§18.2: an authentication problem is blocking — it will fail every message identically")
    void authenticationProblemsAreBlocking(String code) {
        // Act + Assert
        assertThat(SmsGateResponseCatalog.classify(code)).isEqualTo(ErrorClass.BLOCKING);
        assertThat(SmsGateResponseCatalog.countsAsProviderFailure(code)).isTrue();
    }

    @Test
    @DisplayName("§18.2: 27 server side error is retryable and counts against the provider")
    void serverErrorIsRetryableAndCounted() {
        // Act + Assert
        assertThat(SmsGateResponseCatalog.classify("27")).isEqualTo(ErrorClass.RETRYABLE);
        assertThat(SmsGateResponseCatalog.countsAsProviderFailure("27")).isTrue();
    }

    @Test
    @DisplayName("§18.2: the spam limit sends the message elsewhere without blaming the provider")
    void spamLimitIsRetryableButNotAProviderFailure() {
        // Act + Assert
        assertThat(SmsGateResponseCatalog.classify("1")).isEqualTo(ErrorClass.RETRYABLE);
        assertThat(SmsGateResponseCatalog.countsAsProviderFailure("1")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"14", "15", "16", "17", "18", "19", "21", "25"})
    @DisplayName("§18.2: a refusal of the number, the sender or the text is permanent for the message")
    void contentProblemsAreNonRetryable(String code) {
        // Act + Assert
        assertThat(SmsGateResponseCatalog.classify(code)).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(SmsGateResponseCatalog.countsAsProviderFailure(code)).isFalse();
    }

    @Test
    @DisplayName("§18.2 code 20: a blacklisted number is refused and marked as no longer deliverable")
    void blacklistInvalidatesTheRecipient() {
        // Act + Assert
        assertThat(SmsGateResponseCatalog.classify("20")).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(SmsGateResponseCatalog.invalidatesRecipient("20")).isTrue();
        assertThat(SmsGateResponseCatalog.invalidatesRecipient("19")).isFalse();
    }

    @Test
    @DisplayName("SG-02: batch element states fold onto the same code table")
    void batchStatesMapOntoCodes() {
        // Act + Assert
        assertThat(SmsGateResponseCatalog.codeOfItemState("ok")).isEqualTo("0");
        assertThat(SmsGateResponseCatalog.codeOfItemState("blacklist")).isEqualTo("20");
        assertThat(SmsGateResponseCatalog.codeOfItemState("unknown operator")).isEqualTo("21");
        assertThat(SmsGateResponseCatalog.codeOfItemState("empty text")).isEqualTo("18");
        assertThat(SmsGateResponseCatalog.codeOfItemState("something else")).isNull();
    }

    @Test
    @DisplayName("§18.2: FEEDBACK codes map onto the canonical statuses of §6.3")
    void feedbackCodesMapOntoCanonicalStatuses() {
        // Act + Assert
        assertThat(SmsGateStatusCatalog.canonical("0")).contains(MessageStatus.SENT_TO_PROVIDER);
        assertThat(SmsGateStatusCatalog.canonical("1")).contains(MessageStatus.SENT_TO_PROVIDER);
        assertThat(SmsGateStatusCatalog.canonical("2")).contains(MessageStatus.UNDELIVERED);
        assertThat(SmsGateStatusCatalog.canonical("3")).contains(MessageStatus.SENT_TO_PROVIDER);
        assertThat(SmsGateStatusCatalog.canonical("4")).contains(MessageStatus.DELIVERED);
        assertThat(SmsGateStatusCatalog.canonical("5")).contains(MessageStatus.UNDELIVERED);
    }

    @Test
    @DisplayName("SG-03: 6 Unknown is not an outcome and produces no status")
    void unknownCodeProducesNoStatus() {
        // Act + Assert
        assertThat(SmsGateStatusCatalog.canonical("6")).isEmpty();
        assertThat(SmsGateStatusCatalog.isUnknown("6")).isTrue();
    }

    @Test
    @DisplayName("ST-01: 7 InBlackList becomes UNDELIVERED, because REJECTED does not follow SENT_TO_PROVIDER")
    void blacklistReportIsUndeliveredAndInvalidatesTheNumber() {
        // Act + Assert
        assertThat(SmsGateStatusCatalog.canonical("7")).contains(MessageStatus.UNDELIVERED);
        assertThat(SmsGateStatusCatalog.invalidatesRecipient("7")).isTrue();
        assertThat(MessageStatus.SENT_TO_PROVIDER.canTransitionTo(MessageStatus.UNDELIVERED))
                .isTrue();
        assertThat(MessageStatus.SENT_TO_PROVIDER.canTransitionTo(MessageStatus.REJECTED))
                .isFalse();
    }

    @Test
    @DisplayName("§9.2: the weight of a message follows its traffic class and stays inside 0–10")
    void weightFollowsTrafficClass() {
        // Arrange
        Sending sending = Sending.defaults();

        // Act + Assert
        assertThat(sending.weightOf(TrafficClass.CRITICAL_OTP)).isEqualTo(10);
        assertThat(sending.weightOf(TrafficClass.TRANSACTIONAL)).isEqualTo(7);
        assertThat(sending.weightOf(TrafficClass.NOTIFICATION)).isEqualTo(3);
    }

    @Test
    @DisplayName("§18.2: the default per-recipient ceiling stays under the provider's own 50 per hour")
    void defaultRecipientCeilingStaysUnderTheProviderLimit() {
        // Act + Assert
        assertThat(SmsGateProperties.defaults().rateLimit().perRecipientPerHour())
                .isLessThan(50)
                .isEqualTo(Sending.RECIPIENT_MESSAGES_PER_HOUR);
    }

    @Test
    @DisplayName("§9.2: the adapter does not retry inside an attempt, because /api/v2/send is not idempotent")
    void defaultsToNoInnerRetry() {
        // Act + Assert
        assertThat(SmsGateProperties.defaults().resilience().retriesInsideAttempt())
                .isFalse();
    }
}
