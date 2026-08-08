package uz.hamkorbank.commhub.adapter.out.persistence.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.persistence.support.JsonCodec;
import uz.hamkorbank.commhub.application.dto.MessageKey;
import uz.hamkorbank.commhub.application.dto.MessageStatusEvent;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.Attachment;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * The {@code jsonb} columns must survive a write and a read unchanged: a lost field there is a lost
 * message attribute at send time, which no schema constraint would catch (§10.1, DB-04).
 */
class JsonRoundTripTest {

    private static final Currency UZS = Currency.getInstance("UZS");

    private final JsonCodec codec = new JsonCodec();

    @Test
    @DisplayName("message.recipient keeps every address of the recipient")
    void recipientRoundTrips() {
        // Arrange
        Recipient recipient = new Recipient(
                ClientId.of("CL-42"),
                Msisdn.of("998901234567"),
                EmailAddress.of("client@example.uz"),
                List.of(PushToken.of("token-1", PushPlatform.ANDROID), PushToken.of("token-2", PushPlatform.IOS)));

        // Act
        Recipient restored =
                roundTrip(RecipientJson.of(recipient), RecipientJson.class).toDomain();

        // Assert
        assertThat(restored).isEqualTo(recipient);
    }

    @Test
    @DisplayName("message.contents keeps the content of every channel (MP-02)")
    void messageContentsRoundTrip() {
        // Arrange
        MessageContents contents = MessageContents.of(
                SmsContent.of("Код 1234", "HAMKORBANK"),
                new EmailContent(
                        "Выписка",
                        "<p>Выписка</p>",
                        "Выписка",
                        List.of(new Attachment("statement.pdf", "application/pdf", 2048L, "s3://bucket/key")),
                        EmailAddress.of("noreply@hamkorbank.uz")),
                new PushContent("Заголовок", "Текст", Map.of("orderId", "42"), "app://orders/42", null));

        // Act
        MessageContents restored = roundTrip(MessageContentsJson.of(contents), MessageContentsJson.class)
                .toDomain();

        // Assert
        assertThat(restored.channels()).containsExactlyInAnyOrder(Channel.SMS, Channel.EMAIL, Channel.PUSH);
        assertThat(restored.requireForChannel(Channel.SMS)).isEqualTo(contents.requireForChannel(Channel.SMS));
        assertThat(restored.requireForChannel(Channel.EMAIL)).isEqualTo(contents.requireForChannel(Channel.EMAIL));
        assertThat(restored.requireForChannel(Channel.PUSH)).isEqualTo(contents.requireForChannel(Channel.PUSH));
    }

    @Test
    @DisplayName("a single-channel content leaves the other channels absent")
    void singleChannelContentsRoundTrip() {
        // Arrange
        MessageContents contents = MessageContents.of(SmsContent.of("Код 1234"));

        // Act
        MessageContents restored = roundTrip(MessageContentsJson.of(contents), MessageContentsJson.class)
                .toDomain();

        // Assert
        assertThat(restored.channels()).containsExactly(Channel.SMS);
    }

    @Test
    @DisplayName("message.timing keeps the schedule and the TTL (FR-1.4)")
    void timingRoundTrips() {
        // Arrange
        Timing timing = new Timing(
                Instant.parse("2026-08-08T06:00:00Z"),
                Instant.parse("2026-08-08T18:00:00Z"),
                Duration.ofMinutes(15),
                true,
                true,
                LocalTime.of(9, 0),
                LocalTime.of(20, 30));

        // Act
        Timing restored = roundTrip(TimingJson.of(timing), TimingJson.class).toDomain();

        // Assert
        assertThat(restored).isEqualTo(timing);
    }

    @Test
    @DisplayName("an absent timing reads back as immediate delivery")
    void absentTimingBecomesImmediate() {
        // Arrange + Act
        Timing restored = TimingJson.toDomain(codec.read(null, TimingJson.class));

        // Assert
        assertThat(restored).isEqualTo(Timing.immediate());
    }

    @Test
    @DisplayName("message.channel_plan keeps the order of the fallback chain (MP-03)")
    void channelPlanRoundTrips() {
        // Arrange
        ChannelPlan plan = ChannelPlan.fallbackChain(Channel.PUSH, Channel.SMS, Channel.EMAIL);

        // Act
        ChannelPlan restored =
                roundTrip(ChannelPlanJson.of(plan), ChannelPlanJson.class).toDomain();

        // Assert
        assertThat(restored).isEqualTo(plan);
    }

    @Test
    @DisplayName("a tariff keeps its exact amounts — money never goes through a double (FR-6.2)")
    void tariffKeepsScale() {
        // Arrange
        Tariff tariff = new Tariff(Money.of(new BigDecimal("0.1000"), UZS), Money.of(new BigDecimal("123.4567"), UZS));

        // Act
        Tariff restored = TariffJson.toDomain(roundTrip(TariffJson.of(tariff), TariffJson.class));

        // Assert
        assertThat(restored.perMessage().amount()).isEqualByComparingTo("0.1000");
        assertThat(restored.perSegment().amount()).isEqualByComparingTo("123.4567");
        assertThat(restored.currency()).isEqualTo(UZS);
    }

    @Test
    @DisplayName("quiet hours keep the zone they were configured in (FR-5.3)")
    void quietHoursRoundTrip() {
        // Arrange
        QuietHours quietHours = QuietHours.deferring(LocalTime.of(21, 0), LocalTime.of(8, 0));

        // Act
        QuietHours restored = QuietHoursJson.toDomain(roundTrip(QuietHoursJson.of(quietHours), QuietHoursJson.class));

        // Assert
        assertThat(restored).isEqualTo(quietHours);
    }

    @Test
    @DisplayName("a quota with limits round-trips; an unlimited one is stored as nothing (FR-2.6)")
    void quotaConfigRoundTrips() {
        // Arrange
        QuotaConfig quota = new QuotaConfig(
                1_000L,
                20_000L,
                Money.of(new BigDecimal("500.0000"), UZS),
                Money.of(new BigDecimal("9000.0000"), UZS),
                QuotaExhaustionBehavior.BLOCK_AND_ALERT);

        // Act
        QuotaConfig restored = QuotaConfigJson.toDomain(roundTrip(QuotaConfigJson.of(quota), QuotaConfigJson.class));

        // Assert
        assertThat(restored).isEqualTo(quota);
        assertThat(QuotaConfigJson.of(QuotaConfig.unlimited())).isNull();
        assertThat(QuotaConfigJson.toDomain(null)).isEqualTo(QuotaConfig.unlimited());
    }

    @Test
    @DisplayName("an unlimited rate limit is stored as nothing and read back as unlimited (FR-2.5)")
    void rateLimitRoundTrips() {
        // Arrange
        RateLimit rateLimit = new RateLimit(50, 1_000, 5);

        // Act
        RateLimit restored = RateLimitJson.toDomain(roundTrip(RateLimitJson.of(rateLimit), RateLimitJson.class));

        // Assert
        assertThat(restored).isEqualTo(rateLimit);
        assertThat(RateLimitJson.of(RateLimit.unlimited())).isNull();
        assertThat(RateLimitJson.toDomain(null)).isEqualTo(RateLimit.unlimited());
    }

    @Test
    @DisplayName("a routing policy keeps its match and its provider order (FR-8.9)")
    void routingPolicyRoundTrips() {
        // Arrange
        RoutingPolicy.Match match = new RoutingPolicy.Match(
                StreamId.of("mobile-app"), TrafficClass.CRITICAL_OTP, Priority.HIGH, Channel.SMS);
        RoutingPolicy.Action action = new RoutingPolicy.Action(
                Channel.SMS,
                List.of(ProviderCode.of("PLAYMOBILE"), ProviderCode.of("SMSGATE")),
                BalancingStrategy.LEAST_COST);

        // Act
        RoutingPolicy.Match restoredMatch =
                roundTrip(RoutingMatchJson.of(match), RoutingMatchJson.class).toDomain();
        RoutingPolicy.Action restoredAction =
                roundTrip(RoutingActionJson.of(action), RoutingActionJson.class).toDomain();

        // Assert
        assertThat(restoredMatch).isEqualTo(match);
        assertThat(restoredAction).isEqualTo(action);
    }

    @Test
    @DisplayName("an empty match matches everything and survives the round trip")
    void anyMatchRoundTrips() {
        // Arrange
        RoutingPolicy.Match match = RoutingPolicy.Match.any();

        // Act
        RoutingPolicy.Match restored =
                roundTrip(RoutingMatchJson.of(match), RoutingMatchJson.class).toDomain();

        // Assert
        assertThat(restored).isEqualTo(match);
    }

    @Test
    @DisplayName("an outbox payload keeps every field of the §6.4 contract")
    void statusEventRoundTrips() {
        // Arrange
        MessageStatusEvent event = new MessageStatusEvent(
                UUID.fromString("0198f0d0-0000-7000-8000-000000000001"),
                Instant.parse("2026-08-08T12:00:00Z"),
                new MessageKey(
                        StreamId.of("mobile-app"),
                        null,
                        MessageId.of(UUID.fromString("0198f0d0-0000-7000-8000-000000000002")),
                        ExternalMessageId.of("abc0000001"),
                        CorrelationId.of("corr-1")),
                Channel.SMS,
                ProviderCode.of("PLAYMOBILE"),
                MessageStatus.REJECTED,
                "202",
                MessageStatusEvent.StatusReason.of(RejectionReason.PROVIDER_REJECTED, "Invalid number"),
                2);

        // Act
        MessageStatusEvent restored = roundTrip(MessageStatusEventJson.of(event), MessageStatusEventJson.class)
                .toDomain();

        // Assert
        assertThat(restored).isEqualTo(event);
    }

    private <T> T roundTrip(Object payload, Class<T> type) {
        return codec.read(codec.write(payload), type);
    }
}
