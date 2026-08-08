package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.smsProvider;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** Channel configuration: fallback order, balancing and runtime switching (FR-2.2, FR-2.3, FR-2.7). */
class ChannelConfigTest {

    @Test
    @DisplayName("FR-2.2: the first provider of the order is the primary one, the rest are fallbacks")
    void fallbackOrderDefinesPrimaryAndReserves() {
        // Arrange
        ProviderRef primary = smsProvider("PLAYMOBILE").ref();
        ProviderRef reserve = smsProvider("SMSGATE").ref();
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.PRIMARY_ONLY);

        // Act
        channel.updateFallbackOrder(List.of(primary, reserve));

        // Assert
        assertThat(channel.channel()).isEqualTo(Channel.SMS);
        assertThat(channel.primaryProvider()).contains(primary);
        assertThat(channel.providersAfter(primary)).containsExactly(reserve);
        assertThat(channel.providersAfter(reserve)).isEmpty();
        assertThat(channel.isAvailable()).isTrue();
        assertThat(channel.balancingStrategy()).isEqualTo(BalancingStrategy.PRIMARY_ONLY);
    }

    @Test
    @DisplayName("a channel without providers is not available")
    void emptyChannelIsNotAvailable() {
        // Act
        ChannelConfig channel = ChannelConfig.of(Channel.EMAIL, BalancingStrategy.ROUND_ROBIN);

        // Assert
        assertThat(channel.status()).isEqualTo(ChannelStatus.ACTIVE);
        assertThat(channel.isAvailable()).isFalse();
        assertThat(channel.primaryProvider()).isEmpty();
        assertThat(channel.providersAfter(smsProvider("SMSGATE").ref())).isEmpty();
    }

    @Test
    @DisplayName("FR-2.7: maintenance and disabling stop the channel without losing its configuration")
    void statusSwitchesAtRuntime() {
        // Arrange
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.ROUND_ROBIN);
        channel.updateFallbackOrder(List.of(smsProvider("PLAYMOBILE").ref()));

        // Act + Assert
        channel.enterMaintenance();
        assertThat(channel.status()).isEqualTo(ChannelStatus.MAINTENANCE);
        assertThat(channel.isAvailable()).isFalse();

        channel.disable();
        assertThat(channel.status()).isEqualTo(ChannelStatus.DISABLED);

        channel.activate();
        assertThat(channel.isAvailable()).isTrue();
        assertThat(channel.fallbackOrder()).hasSize(1);
    }

    @Test
    @DisplayName("strategy and channel-level quiet hours are editable (FR-2.3, FR-5.3)")
    void strategyAndQuietHoursAreEditable() {
        // Arrange
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.PRIMARY_ONLY);

        // Act
        channel.updateBalancingStrategy(BalancingStrategy.LEAST_COST);
        channel.updateQuietHours(QuietHours.rejecting(LocalTime.of(22, 0), LocalTime.of(8, 0)));

        // Assert
        assertThat(channel.balancingStrategy()).isEqualTo(BalancingStrategy.LEAST_COST);
        assertThat(channel.quietHours()).isPresent();
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> channel.updateBalancingStrategy(null));
    }

    @Test
    @DisplayName("providers of another channel or duplicates are refused in the order")
    void fallbackOrderIsValidated() {
        // Arrange
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.ROUND_ROBIN);
        ProviderRef sms = smsProvider("PLAYMOBILE").ref();
        ProviderRef push = new ProviderRef(
                ProviderId.newId(), ProviderCode.of("FCM"), Channel.PUSH, AdapterType.of("fcm-http-v1"));

        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> channel.updateFallbackOrder(List.of(sms, push)))
                .withMessageContaining("does not serve channel SMS");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> channel.updateFallbackOrder(List.of(sms, sms)))
                .withMessageContaining("must be distinct");
    }

    @Test
    @DisplayName("FR-2.6: a channel carries a quota of its own, unlimited until configured")
    void channelQuotaIsConfigurable() {
        // Arrange
        ChannelConfig channel = ChannelConfig.of(Channel.SMS, BalancingStrategy.ROUND_ROBIN);

        // Act
        channel.updateQuota(QuotaConfig.ofCounts(500L, null, QuotaExhaustionBehavior.ALERT_ONLY));

        // Assert
        assertThat(ChannelConfig.of(Channel.EMAIL, BalancingStrategy.PRIMARY_ONLY)
                        .quota()
                        .isUnlimited())
                .isTrue();
        assertThat(channel.quota().dailyCountLimit()).contains(500L);
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> channel.updateQuota(null));
    }
}
