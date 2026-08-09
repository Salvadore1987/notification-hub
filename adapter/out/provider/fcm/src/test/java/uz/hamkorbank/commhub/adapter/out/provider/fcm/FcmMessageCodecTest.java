package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/** The {@code messages:send} document of FCM HTTP v1 (§9.4.1, PU-03, PU-05, PU-13). */
class FcmMessageCodecTest {

    private static final Instant NOW = Instant.parse("2026-08-08T10:00:00Z");

    private final FcmJson json = new FcmJson();
    private final FcmMessageCodec codec = new FcmMessageCodec(json);

    @Test
    @DisplayName("PU-03: an Android notification carries token, notification, data, priority and ttl")
    void writesAnAndroidNotification() {
        // Arrange
        PushSubmission submission = submission(
                PushPlatform.ANDROID,
                TrafficClass.TRANSACTIONAL,
                new PushContent(
                        "Hamkorbank", "Hisobingiz to'ldirildi", Map.of("accountId", "4321"), "hb://accounts", null),
                Timing.withTtl(Duration.ofMinutes(5)),
                null);

        // Act
        JsonNode document = json.readOrNull(codec.encode(submission, FcmProperties.Sending.defaults(), false, NOW));

        // Assert
        JsonNode message = document.get("message");
        assertThat(message.get("token").asString()).isEqualTo("device-a");
        assertThat(message.get("notification").get("title").asString()).isEqualTo("Hamkorbank");
        assertThat(message.get("data").get("accountId").asString()).isEqualTo("4321");
        assertThat(message.get("data").get(FcmMessageCodec.DEEP_LINK_FIELD).asString())
                .isEqualTo("hb://accounts");
        assertThat(message.get("android").get("priority").asString()).isEqualTo("HIGH");
        assertThat(message.get("android").get("ttl").asString()).isEqualTo("300s");
        assertThat(document.has("validate_only")).isFalse();
    }

    @Test
    @DisplayName("PU-03: bulk traffic is sent at NORMAL priority — HIGH is for OTP and transactional")
    void bulkTrafficIsNotHighPriority() {
        // Arrange
        PushSubmission submission = submission(
                PushPlatform.ANDROID,
                TrafficClass.NOTIFICATION,
                PushContent.of("Hamkorbank", "Aksiya"),
                Timing.immediate(),
                "promo");

        // Act
        JsonNode message = json.readOrNull(codec.encode(submission, FcmProperties.Sending.defaults(), false, NOW))
                .get("message");

        // Assert
        assertThat(message.get("android").get("priority").asString()).isEqualTo("NORMAL");
        assertThat(message.get("android").get("collapse_key").asString()).isEqualTo("promo");
    }

    @Test
    @DisplayName("PU-05: an iOS token gets the apns block, so the mode change does not change delivery")
    void writesTheApnsBlockForIos() {
        // Arrange
        PushSubmission submission = submission(
                PushPlatform.IOS,
                TrafficClass.CRITICAL_OTP,
                PushContent.of("Hamkorbank", "Kod: 4821"),
                Timing.withTtl(Duration.ofMinutes(5)),
                null);

        // Act
        JsonNode message = json.readOrNull(codec.encode(submission, FcmProperties.Sending.defaults(), false, NOW))
                .get("message");

        // Assert
        JsonNode headers = message.get("apns").get("headers");
        assertThat(headers.get("apns-priority").asString()).isEqualTo("10");
        assertThat(headers.get("apns-push-type").asString()).isEqualTo("alert");
        assertThat(headers.get("apns-expiration").asString())
                .isEqualTo(String.valueOf(NOW.plus(Duration.ofMinutes(5)).getEpochSecond()));
        assertThat(message.get("apns")
                        .get("payload")
                        .get("aps")
                        .get("alert")
                        .get("body")
                        .asString())
                .isEqualTo("Kod: 4821");
    }

    @Test
    @DisplayName("§9.4: an Android token gets no apns block — it would be dead weight on every request")
    void omitsTheApnsBlockForAndroid() {
        // Arrange
        PushSubmission submission = submission(
                PushPlatform.ANDROID,
                TrafficClass.TRANSACTIONAL,
                PushContent.of("Hamkorbank", "Hisobingiz to'ldirildi"),
                Timing.immediate(),
                null);

        // Act
        JsonNode message = json.readOrNull(codec.encode(submission, FcmProperties.Sending.defaults(), false, NOW))
                .get("message");

        // Assert
        assertThat(message.has("apns")).isFalse();
    }

    @Test
    @DisplayName("PU-13: a test send is a dry run — the token is verified and nothing is delivered")
    void marksATestSendAsValidateOnly() {
        // Arrange
        PushSubmission submission = submission(
                PushPlatform.ANDROID,
                TrafficClass.TRANSACTIONAL,
                PushContent.of("Hamkorbank", "Test"),
                Timing.immediate(),
                null);

        // Act
        JsonNode document = json.readOrNull(codec.encode(submission, FcmProperties.Sending.defaults(), true, NOW));

        // Assert
        assertThat(document.get("validate_only").asBoolean()).isTrue();
    }

    private static PushSubmission submission(
            PushPlatform platform, TrafficClass trafficClass, PushContent content, Timing timing, String collapseKey) {
        ProviderRef provider =
                new ProviderRef(ProviderId.newId(), ProviderCode.of("FCM"), Channel.PUSH, AdapterType.of("fcm-http"));
        return new PushSubmission(
                provider,
                MessageId.of(UuidV7.generate()),
                PushToken.of("device-a", platform),
                content,
                timing,
                collapseKey,
                new SubmissionContext(trafficClass, Priority.NORMAL, CorrelationId.of("corr-1"), false));
    }
}
