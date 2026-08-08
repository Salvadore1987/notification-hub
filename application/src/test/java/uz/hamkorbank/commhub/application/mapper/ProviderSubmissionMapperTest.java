package uz.hamkorbank.commhub.application.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.EXTERNAL_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.androidToken;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushRecipient;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;

/** Translation of a canonical message into the submission an adapter receives (MP-05, PU-03). */
class ProviderSubmissionMapperTest {

    private static final Provider FCM = pushProvider("FCM", "fcm-http");

    private final ProviderSubmissionMapper mapper = new ProviderSubmissionMapperImpl();

    @Test
    @DisplayName("PU-03: the requested collapse group travels as a transport instruction, not as app data")
    void liftsTheCollapseKeyOutOfTheDataPayload() {
        // Arrange
        Message message = pushMessage(TrafficClass.NOTIFICATION, Map.of("collapseKey", "balance", "accountId", "4321"));

        // Act
        PushSubmission submission = mapper.toPushSubmission(message, FCM.ref(), androidToken("device-a"));

        // Assert
        assertThat(submission.collapseKey()).isEqualTo("balance");
        assertThat(submission.content().data()).containsOnlyKeys("accountId");
    }

    @Test
    @DisplayName("PU-03/TC-01: an OTP is never collapsed — a password the customer never sees is the failure")
    void neverCollapsesOtp() {
        // Arrange
        Message message = pushMessage(TrafficClass.CRITICAL_OTP, Map.of("collapseKey", "otp"));

        // Act
        PushSubmission submission = mapper.toPushSubmission(message, FCM.ref(), androidToken("device-a"));

        // Assert
        assertThat(submission.collapseKey()).isNull();
        assertThat(submission.content().data()).isEmpty();
    }

    @Test
    @DisplayName("a message that asked for no collapsing carries its data through untouched")
    void leavesOrdinaryDataAlone() {
        // Arrange
        Message message = pushMessage(TrafficClass.TRANSACTIONAL, Map.of("accountId", "4321"));

        // Act
        PushSubmission submission = mapper.toPushSubmission(message, FCM.ref(), androidToken("device-a"));

        // Assert
        assertThat(submission.collapseKey()).isNull();
        assertThat(submission.content().data()).containsEntry("accountId", "4321");
    }

    private static Message pushMessage(TrafficClass trafficClass, Map<String, String> data) {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, EXTERNAL_ID, trafficClass),
                pushRecipient(androidToken("device-a")),
                PushContent.of("Hamkorbank", "Hisobingiz to'ldirildi", data),
                NOW);
    }
}
