package uz.hamkorbank.commhub.application.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsChannel;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.stream;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.QuotaCounterPort;
import uz.hamkorbank.commhub.application.port.out.QuotaScope;
import uz.hamkorbank.commhub.application.port.out.QuotaWindow;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.QuotaVerdict;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;

/** Quotas of the stream, the channel and the provider (FR-2.6). */
class QuotaGuardTest {

    private QuotaCounterPort counters;
    private MetricsPort metrics;
    private QuotaGuard guard;

    @BeforeEach
    void setUp() {
        counters = mock(QuotaCounterPort.class);
        metrics = mock(MetricsPort.class);
        when(counters.usage(any(), any(), any())).thenReturn(QuotaConfig.Usage.none());
        guard = new QuotaGuard(counters, metrics);
    }

    @Test
    @DisplayName("FR-2.6: an unlimited dimension costs no counter read at all")
    void unlimitedQuotasAreNotRead() {
        // Arrange
        QuotaSubject subject = subject(QuotaConfig.unlimited(), QuotaConfig.unlimited(), QuotaConfig.unlimited());

        // Act
        PipelineVerdict verdict = guard.check(subject, 1L, null, NOW);

        // Assert
        assertThat(verdict.isRejected()).isFalse();
        verify(counters, never()).usage(any(), any(), any());
    }

    @Test
    @DisplayName("FR-2.6: an exhausted stream quota blocks the send and names the dimension")
    void blocksOnStreamQuota() {
        // Arrange
        QuotaSubject subject = subject(
                QuotaConfig.ofCounts(10L, null, QuotaExhaustionBehavior.BLOCK_AND_ALERT),
                QuotaConfig.unlimited(),
                QuotaConfig.unlimited());
        when(counters.usage(eq(QuotaScope.ofStream(STREAM_ID)), eq(QuotaWindow.DAY), any()))
                .thenReturn(QuotaConfig.Usage.of(10L));

        // Act
        PipelineVerdict verdict = guard.check(subject, 1L, null, NOW);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.reason()).isEqualTo(RejectionReason.QUOTA_EXCEEDED);
        assertThat(verdict.detail()).contains("stream " + STREAM_ID.value());
        verify(metrics).quotaBreached(STREAM_ID, Channel.SMS, QuotaVerdict.BLOCKED);
    }

    @Test
    @DisplayName("FR-2.6: the provider quota is checked too, and it blocks on its own")
    void blocksOnProviderQuota() {
        // Arrange
        Provider provider = smsProvider("PLAYMOBILE");
        provider.updateQuota(QuotaConfig.ofCounts(100L, null, QuotaExhaustionBehavior.BLOCK_AND_ALERT));
        QuotaSubject subject = new QuotaSubject(stream(), Channel.SMS, smsChannel(List.of(provider)), provider);
        when(counters.usage(eq(QuotaScope.ofProvider(Channel.SMS, provider.id())), any(), any()))
                .thenReturn(QuotaConfig.Usage.of(100L));

        // Act
        PipelineVerdict verdict = guard.check(subject, 1L, null, NOW);

        // Assert
        assertThat(verdict.isRejected()).isTrue();
        assertThat(verdict.detail()).contains("provider PLAYMOBILE");
    }

    @Test
    @DisplayName("FR-2.6: ALERT_ONLY reports the breach and still lets the message through")
    void alertOnlyDoesNotBlock() {
        // Arrange
        QuotaSubject subject = subject(
                QuotaConfig.ofCounts(1L, null, QuotaExhaustionBehavior.ALERT_ONLY),
                QuotaConfig.unlimited(),
                QuotaConfig.unlimited());
        when(counters.usage(any(), any(), any())).thenReturn(QuotaConfig.Usage.of(5L));

        // Act
        PipelineVerdict verdict = guard.check(subject, 1L, null, NOW);

        // Assert
        assertThat(verdict.isRejected()).isFalse();
        verify(metrics).quotaBreached(STREAM_ID, Channel.SMS, QuotaVerdict.ALERT);
    }

    @Test
    @DisplayName("FR-2.6: registration writes exactly the scopes the check reads")
    void registersTheSameScopesItChecks() {
        // Arrange
        Provider provider = smsProvider("PLAYMOBILE");
        provider.updateQuota(QuotaConfig.ofCounts(1_000L, null, QuotaExhaustionBehavior.ALERT_ONLY));
        ChannelConfig channelConfig = smsChannel(List.of(provider));
        channelConfig.updateQuota(QuotaConfig.ofCounts(5_000L, null, QuotaExhaustionBehavior.ALERT_ONLY));
        Stream source = stream();
        source.updateQuota(QuotaConfig.ofCounts(500L, null, QuotaExhaustionBehavior.ALERT_ONLY));

        // Act
        guard.register(new QuotaSubject(source, Channel.SMS, channelConfig, provider), 2L, null, NOW);

        // Assert
        verify(counters).register(eq(QuotaScope.ofStream(STREAM_ID)), eq(2L), any(), eq(NOW));
        verify(counters).register(eq(QuotaScope.ofChannel(Channel.SMS)), eq(2L), any(), eq(NOW));
        verify(counters).register(eq(QuotaScope.ofProvider(Channel.SMS, provider.id())), eq(2L), any(), eq(NOW));
    }

    private static QuotaSubject subject(QuotaConfig streamQuota, QuotaConfig channelQuota, QuotaConfig providerQuota) {
        Stream source = stream();
        source.updateQuota(streamQuota);
        Provider provider = smsProvider("PLAYMOBILE");
        provider.updateQuota(providerQuota);
        ChannelConfig channelConfig = smsChannel(List.of(provider));
        channelConfig.updateQuota(channelQuota);
        return new QuotaSubject(source, Channel.SMS, channelConfig, provider);
    }
}
