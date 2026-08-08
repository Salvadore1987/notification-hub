package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.uzs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;

/** Provider profile: selectability, tariffs and limits (FR-2.1, FR-2.5, FR-2.7, PR-02). */
class ProviderTest {

    @Test
    @DisplayName("a registered provider is enabled with unknown health and exposes its reference (MP-05)")
    void registrationDefaults() {
        // Act
        Provider provider = newProvider();

        // Assert
        assertThat(provider.isEnabled()).isTrue();
        assertThat(provider.isInMaintenance()).isFalse();
        assertThat(provider.health()).isEqualTo(ProviderHealthStatus.UNKNOWN);
        assertThat(provider.isSelectable()).isTrue();
        assertThat(provider.ref().code()).isEqualTo(ProviderCode.of("PLAYMOBILE"));
        assertThat(provider.ref().channel()).isEqualTo(Channel.SMS);
        assertThat(provider.ref().adapterType()).isEqualTo(AdapterType.of("playmobile-http"));
        assertThat(provider.rateLimit().isUnlimited()).isTrue();
        assertThat(provider.tariff()).isEmpty();
        assertThat(provider.credentialsRef()).isEmpty();
        assertThat(provider.weight()).isEqualTo(Provider.DEFAULT_WEIGHT);
    }

    @Test
    @DisplayName("FR-2.6: a provider carries its own quota, unlimited until one is configured")
    void providerQuotaIsConfigurable() {
        // Arrange
        Provider provider = newProvider();

        // Act
        provider.updateQuota(QuotaConfig.ofCounts(1_000L, 20_000L, QuotaExhaustionBehavior.BLOCK_AND_ALERT));

        // Assert
        assertThat(newProvider().quota().isUnlimited()).isTrue();
        assertThat(provider.quota().dailyCountLimit()).contains(1_000L);
        assertThat(provider.quota().monthlyCountLimit()).contains(20_000L);
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> provider.updateQuota(null));
    }

    @Test
    @DisplayName("FR-2.7, FR-6.3: disabling, maintenance and DOWN health all remove a provider from routing")
    void selectabilityFollowsStateAndHealth() {
        // Arrange
        Provider provider = newProvider();

        // Act + Assert
        provider.disable();
        assertThat(provider.isSelectable()).isFalse();

        provider.enable();
        provider.enterMaintenance();
        assertThat(provider.isSelectable()).isFalse();

        provider.leaveMaintenance();
        provider.markHealth(ProviderHealthStatus.DOWN);
        assertThat(provider.isSelectable()).isFalse();

        provider.markHealth(ProviderHealthStatus.DEGRADED);
        assertThat(provider.isSelectable()).isTrue();
    }

    @Test
    @DisplayName("FR-6.2: cost follows the tariff and the segment count")
    void costFollowsTheTariff() {
        // Arrange
        Provider provider = newProvider();

        // Act
        provider.updateTariff(new Tariff(uzs("10"), uzs("25")));

        // Assert
        assertThat(provider.costOf(1)).contains(uzs("35"));
        assertThat(provider.costOf(3)).contains(uzs("85"));
        assertThat(provider.tariff()).isPresent();
    }

    @Test
    @DisplayName("FR-2.5: limits and weights are editable at runtime")
    void limitsAreEditable() {
        // Arrange
        Provider provider = newProvider();

        // Act
        provider.updateRateLimit(new RateLimit(100, 3_000, 50));
        provider.updateWeight(30);
        provider.updateCredentialsRef("vault://providers/playmobile");

        // Assert
        assertThat(provider.rateLimit().hasTpsLimit()).isTrue();
        assertThat(provider.rateLimit().hasPerMinuteLimit()).isTrue();
        assertThat(provider.rateLimit().hasPerRecipientLimit()).isTrue();
        assertThat(provider.weight()).isEqualTo(30);
        assertThat(provider.credentialsRef()).contains("vault://providers/playmobile");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> provider.updateWeight(0));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> provider.updateWeight(Provider.MAX_WEIGHT + 1));
    }

    @Test
    @DisplayName("settings are copied with a fluent API and validate the weight")
    void settingsAreImmutable() {
        // Arrange
        Provider.Settings settings = Provider.Settings.defaults();

        // Act
        Provider.Settings updated = settings.withWeight(5)
                .withTariff(Tariff.perSegment(uzs("25")))
                .withRateLimit(RateLimit.ofTps(50))
                .withCredentialsRef("vault://x");

        // Assert
        assertThat(settings.weight()).isEqualTo(Provider.DEFAULT_WEIGHT);
        assertThat(updated.weight()).isEqualTo(5);
        assertThat(updated.tariff()).isNotNull();
        assertThat(updated.rateLimit().tps()).isEqualTo(50);
        assertThat(updated.credentialsRef()).isEqualTo("vault://x");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> settings.withWeight(-1));
    }

    @Test
    @DisplayName("a tariff needs at least one component and one currency")
    void tariffInvariants() {
        // Act + Assert
        assertThat(Tariff.perMessage(uzs("100")).costOf(3)).isEqualTo(uzs("100"));
        assertThat(Tariff.perSegment(uzs("25")).costOf(0)).isEqualTo(uzs("25"));
        assertThat(Tariff.perSegment(uzs("25")).currency()).isEqualTo(uzs("25").currency());
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new Tariff(null, null));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new Tariff(uzs("1"), Money.of("1", "USD")))
                .withMessageContaining("same currency");
    }

    @Test
    @DisplayName("rate limits reject negative values")
    void rateLimitInvariants() {
        // Act + Assert
        assertThat(RateLimit.unlimited().isUnlimited()).isTrue();
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new RateLimit(-1, 0, 0));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new RateLimit(0, -1, 0));
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new RateLimit(0, 0, -1));
    }

    private static Provider newProvider() {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of("PLAYMOBILE"),
                Channel.SMS,
                AdapterType.of("playmobile-http"),
                Provider.Settings.defaults());
    }
}
