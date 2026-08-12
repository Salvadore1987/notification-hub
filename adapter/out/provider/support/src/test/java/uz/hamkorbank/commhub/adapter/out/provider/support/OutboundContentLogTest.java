package uz.hamkorbank.commhub.adapter.out.provider.support;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;

/**
 * The local stand's plaintext trace: everything or nothing, and nothing by default (SEC-06).
 *
 * <p>The point of the switch is that the default cannot be got wrong, so the first test is the one
 * that matters: an instance built the way the deployment builds it writes no line at all.
 */
class OutboundContentLogTest {

    private static final String TEXT = "Kod: 4821. Nikomu ne soobshchayte.";

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureTheTrace() {
        logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(OutboundContentLog.LOGGER_NAME);
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseTheTrace() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("SEC-06: switched off — and it is off unless somebody says otherwise — nothing is written")
    void writesNothingByDefault() {
        // Arrange
        OutboundContentLog log = new OutboundContentLog(new ContentLogProperties(null));

        // Act
        log.record(smsSubmission());
        log.record(emailSubmission());
        log.record(pushSubmission());

        // Assert
        assertThat(log.isEnabled()).isFalse();
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("the stand sees the number and the text exactly as they are being sent")
    void writesTheSmsInClear() {
        // Arrange
        OutboundContentLog log = enabled();

        // Act
        log.record(smsSubmission());

        // Assert
        assertThat(rendered()).contains("998901234567").contains(TEXT).contains("PLAYMOBILE");
    }

    @Test
    @DisplayName("a bulk chunk is one line per message, not one line per request")
    void writesEveryMessageOfAChunk() {
        // Arrange
        OutboundContentLog log = enabled();

        // Act
        log.recordAll(List.of(smsSubmission(), smsSubmission()));

        // Assert
        assertThat(appender.list).hasSize(2);
    }

    @Test
    @DisplayName("EM-01: an email shows subject and text; the HTML alternative shows its size only")
    void writesTheEmailWithoutItsMarkup() {
        // Arrange
        OutboundContentLog log = enabled();

        // Act
        log.record(emailSubmission());

        // Assert
        assertThat(rendered())
                .contains("ivan@example.com")
                .contains("Vypiska")
                .contains("Vash balans")
                .contains("chars]")
                .doesNotContain("<html>");
    }

    @Test
    @DisplayName("PU-06: a push shows the token it is addressed to and the payload it carries")
    void writesThePushWithItsToken() {
        // Arrange
        OutboundContentLog log = enabled();

        // Act
        log.record(pushSubmission());

        // Assert
        assertThat(rendered()).contains("device-token-1").contains("Perevod").contains("orderId");
    }

    @Test
    @DisplayName("a missing submission is not a reason for a trace to fail a send")
    void survivesNullSubmissions() {
        // Arrange
        OutboundContentLog log = enabled();

        // Act
        log.record((SmsSubmission) null);
        log.record((EmailSubmission) null);
        log.record((PushSubmission) null);
        log.recordAll(null);

        // Assert
        assertThat(appender.list).isEmpty();
    }

    private static OutboundContentLog enabled() {
        return new OutboundContentLog(new ContentLogProperties(true));
    }

    private String rendered() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + '\n' + right);
    }

    private static SmsSubmission smsSubmission() {
        return new SmsSubmission(
                providerRef("PLAYMOBILE", Channel.SMS, "playmobile-http"),
                MessageId.newId(),
                null,
                Msisdn.of("998901234567"),
                SmsContent.of(TEXT),
                Timing.immediate(),
                null,
                context());
    }

    private static EmailSubmission emailSubmission() {
        return new EmailSubmission(
                providerRef("SMTP", Channel.EMAIL, "smtp"),
                MessageId.newId(),
                EmailAddress.of("ivan@example.com"),
                new EmailContent(
                        "Vypiska", "<html>Vash balans: 100</html>", "Vash balans: 100 000 sum", List.of(), null),
                context());
    }

    private static PushSubmission pushSubmission() {
        return new PushSubmission(
                providerRef("FCM", Channel.PUSH, "fcm-http"),
                MessageId.newId(),
                new PushToken("device-token-1", PushPlatform.ANDROID),
                new PushContent("Perevod", "Postupil perevod 100 000 sum", Map.of("orderId", "42"), null, null),
                Timing.immediate(),
                null,
                context());
    }

    private static ProviderRef providerRef(String code, Channel channel, String adapterType) {
        return new ProviderRef(ProviderId.newId(), ProviderCode.of(code), channel, AdapterType.of(adapterType));
    }

    private static SubmissionContext context() {
        return new SubmissionContext(TrafficClass.CRITICAL_OTP, Priority.HIGH, CorrelationId.of("corr-1"), false);
    }
}
