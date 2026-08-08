package uz.hamkorbank.commhub.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.service.support.PipelineStages;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** OBS-01: what the pipeline reports becomes a counter or a timer with bounded, closed tags. */
class MicrometerMetricsAdapterTest {

    private static final StreamId STREAM = StreamId.of("chakana");

    private static final ProviderRef PLAYMOBILE = new ProviderRef(
            ProviderId.newId(), ProviderCode.of("PLAYMOBILE"), Channel.SMS, AdapterType.of("playmobile"));

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsPort metrics = new MicrometerMetricsAdapter(registry);

    @Test
    @DisplayName("an accepted message is counted by stream, class and channel")
    void countsAcceptedMessages() {
        // Arrange + Act
        metrics.messageAccepted(STREAM, TrafficClass.CRITICAL_OTP, Channel.SMS, false);
        metrics.messageAccepted(STREAM, TrafficClass.CRITICAL_OTP, Channel.SMS, false);

        // Assert
        assertThat(registry.get(MetricNames.MESSAGES_ACCEPTED)
                        .tag(MetricNames.TAG_STREAM, "chakana")
                        .tag(MetricNames.TAG_TRAFFIC_CLASS, "CRITICAL_OTP")
                        .tag(MetricNames.TAG_CHANNEL, "SMS")
                        .tag(MetricNames.TAG_TEST, "false")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("FR-7.4: a test send is a separate series, not a dropped one")
    void separatesTestSendsFromBusinessFigures() {
        // Arrange + Act
        metrics.messageAccepted(STREAM, TrafficClass.TRANSACTIONAL, Channel.SMS, true);
        metrics.statusChanged(MessageStatus.DELIVERED, Channel.SMS, PLAYMOBILE, true);

        // Assert
        assertThat(registry.get(MetricNames.MESSAGES_ACCEPTED)
                        .tag(MetricNames.TAG_TEST, "true")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.find(MetricNames.MESSAGES_ACCEPTED)
                        .tag(MetricNames.TAG_TEST, "false")
                        .counter())
                .isNull();
        assertThat(registry.get(MetricNames.MESSAGE_STATUS)
                        .tag(MetricNames.TAG_TEST, "true")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a dimension that is not known yet becomes 'none' rather than a dropped observation")
    void tagsUnknownDimensionsAsNone() {
        // Arrange + Act
        metrics.messageAccepted(STREAM, TrafficClass.NOTIFICATION, null, false);
        metrics.statusChanged(MessageStatus.REJECTED, null, null, false);

        // Assert
        assertThat(registry.get(MetricNames.MESSAGES_ACCEPTED)
                        .tag(MetricNames.TAG_CHANNEL, MetricNames.NONE)
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(MetricNames.MESSAGE_STATUS)
                        .tag(MetricNames.TAG_PROVIDER, MetricNames.NONE)
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PR-03: a provider call is timed and tagged with its provider, channel and result")
    void timesProviderCalls() {
        // Arrange + Act
        metrics.providerCall(PLAYMOBILE, AttemptResult.ACCEPTED, Duration.ofMillis(250));

        // Assert
        assertThat(registry.get(MetricNames.PROVIDER_CALLS)
                        .tag(MetricNames.TAG_PROVIDER, "PLAYMOBILE")
                        .tag(MetricNames.TAG_CHANNEL, "SMS")
                        .tag(MetricNames.TAG_RESULT, "ACCEPTED")
                        .timer()
                        .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(250);
    }

    @Test
    @DisplayName("TC-01: the SLA stage is timed per traffic class")
    void timesPipelineStages() {
        // Arrange + Act
        metrics.stageLatency(PipelineStages.ACCEPT_TO_PROVIDER, TrafficClass.CRITICAL_OTP, Duration.ofSeconds(2));

        // Assert
        assertThat(registry.get(MetricNames.PIPELINE_STAGE)
                        .tag(MetricNames.TAG_STAGE, PipelineStages.ACCEPT_TO_PROVIDER)
                        .tag(MetricNames.TAG_TRAFFIC_CLASS, "CRITICAL_OTP")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("compliance findings are counted where the alerts of OBS-04 read them")
    void countsComplianceEvents() {
        // Arrange + Act
        metrics.messageRejected(STREAM, RejectionReason.SUPPRESSED);
        metrics.messageDuplicate(STREAM);
        metrics.quotaBreached(STREAM, Channel.SMS, QuotaVerdict.BLOCKED);
        metrics.frequencyCapExceeded(Channel.SMS, 11, 10);
        metrics.panDetected(Channel.SMS, true);
        metrics.recipientSuppressed(Channel.EMAIL, SuppressionReason.HARD_BOUNCE);

        // Assert
        assertThat(registry.get(MetricNames.MESSAGES_REJECTED)
                        .tag(MetricNames.TAG_REASON, "SUPPRESSED")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(MetricNames.MESSAGES_DUPLICATE).counter().count())
                .isEqualTo(1);
        assertThat(registry.get(MetricNames.QUOTA_BREACHED)
                        .tag(MetricNames.TAG_VERDICT, "BLOCKED")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(MetricNames.FREQUENCY_CAP_EXCEEDED).counter().count())
                .isEqualTo(1);
        assertThat(registry.get(MetricNames.PAN_DETECTED)
                        .tag(MetricNames.TAG_BLOCKED, "true")
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(registry.get(MetricNames.RECIPIENTS_SUPPRESSED)
                        .tag(MetricNames.TAG_REASON, "HARD_BOUNCE")
                        .counter()
                        .count())
                .isEqualTo(1);
    }
}
