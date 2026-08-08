package uz.hamkorbank.commhub.domain.model.type;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Canonical status model and its transition table (SRS §6.3, ST-01…ST-03). */
class MessageStatusTest {

    @Test
    @DisplayName("ST-02: terminal statuses are exactly the ones listed in the SRS")
    void terminalStatusesMatchTheSpecification() {
        // Act
        Set<MessageStatus> actual = MessageStatus.terminalStatuses();

        // Assert
        assertThat(actual)
                .containsExactlyInAnyOrder(
                        MessageStatus.DELIVERED,
                        MessageStatus.UNDELIVERED,
                        MessageStatus.EXPIRED,
                        MessageStatus.REJECTED,
                        MessageStatus.DUPLICATE,
                        MessageStatus.CANCELLED,
                        MessageStatus.FAILED);
    }

    @ParameterizedTest
    @CsvSource({
        "ACCEPTED,VALIDATED",
        "ACCEPTED,REJECTED",
        "ACCEPTED,DUPLICATE",
        "VALIDATED,ROUTED",
        "ROUTED,QUEUED",
        "QUEUED,SENDING",
        "SENDING,SENT_TO_PROVIDER",
        "SENDING,RETRYING",
        "SENT_TO_PROVIDER,DELIVERED",
        "SENT_TO_PROVIDER,UNDELIVERED",
        "SENT_TO_PROVIDER,RETRYING",
        "RETRYING,SENDING",
        "RETRYING,FAILED",
        "FAILED,QUEUED"
    })
    @DisplayName("transitions of the SRS §6.3 lifecycle are allowed")
    void allowsSpecifiedTransitions(MessageStatus from, MessageStatus to) {
        // Act + Assert
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "ACCEPTED,ROUTED",
        "ACCEPTED,DELIVERED",
        "VALIDATED,SENDING",
        "ROUTED,SENT_TO_PROVIDER",
        "QUEUED,DELIVERED",
        "SENDING,DELIVERED",
        "DELIVERED,UNDELIVERED",
        "REJECTED,VALIDATED",
        "DUPLICATE,QUEUED",
        "CANCELLED,QUEUED",
        "EXPIRED,QUEUED"
    })
    @DisplayName("transitions that skip a stage or reopen a terminal status are refused")
    void refusesUnspecifiedTransitions(MessageStatus from, MessageStatus to) {
        // Act + Assert
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(MessageStatus.class)
    @DisplayName("terminal statuses have no outgoing transitions, except the DLQ retry of FAILED")
    void terminalStatusesAreClosed(MessageStatus status) {
        // Act + Assert
        if (!status.isTerminal()) {
            assertThat(status.allowedTransitions()).isNotEmpty();
        } else if (status == MessageStatus.FAILED) {
            assertThat(status.allowedTransitions()).containsExactly(MessageStatus.QUEUED);
        } else {
            assertThat(status.allowedTransitions()).isEmpty();
        }
        assertThat(status.isInFlight()).isEqualTo(!status.isTerminal());
    }

    @Test
    @DisplayName("null is never a valid target status")
    void nullIsNotATransitionTarget() {
        // Act + Assert
        assertThat(MessageStatus.ACCEPTED.canTransitionTo(null)).isFalse();
    }

    @Test
    @DisplayName("in-flight statuses are the ones used by partial indexes (DB-05)")
    void inFlightStatusesAreTheNonTerminalOnes() {
        // Act
        EnumSet<MessageStatus> inFlight = EnumSet.noneOf(MessageStatus.class);
        for (MessageStatus status : MessageStatus.values()) {
            if (status.isInFlight()) {
                inFlight.add(status);
            }
        }

        // Assert
        assertThat(inFlight)
                .containsExactlyInAnyOrder(
                        MessageStatus.ACCEPTED,
                        MessageStatus.VALIDATED,
                        MessageStatus.ROUTED,
                        MessageStatus.QUEUED,
                        MessageStatus.SENDING,
                        MessageStatus.SENT_TO_PROVIDER,
                        MessageStatus.RETRYING);
    }
}
