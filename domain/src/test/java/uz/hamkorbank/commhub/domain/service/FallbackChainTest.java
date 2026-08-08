package uz.hamkorbank.commhub.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static uz.hamkorbank.commhub.domain.DomainFixtures.smsChannel;
import static uz.hamkorbank.commhub.domain.DomainFixtures.smsProvider;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** Reserve order inside a channel and across channels (FR-2.2, MP-03). */
class FallbackChainTest {

    private FallbackChain fallbackChain;

    @BeforeEach
    void setUp() {
        fallbackChain = new FallbackChain();
    }

    @Test
    @DisplayName("FR-2.2: the chain keeps the configured order")
    void chainKeepsTheConfiguredOrder() {
        // Arrange
        Provider primary = smsProvider("PLAYMOBILE");
        Provider reserve = smsProvider("SMSGATE");
        ChannelConfig channel = smsChannel(BalancingStrategy.PRIMARY_ONLY, List.of(primary, reserve));

        // Act
        List<ProviderRef> chain = fallbackChain.providerChain(channel, List.of(reserve, primary));

        // Assert
        assertThat(chain).containsExactly(primary.ref(), reserve.ref());
    }

    @Test
    @DisplayName("FR-2.7, PR-02: disabled, maintenance and DOWN providers drop out of the chain")
    void unusableProvidersDropOut() {
        // Arrange
        Provider disabled = smsProvider("PLAYMOBILE");
        Provider down = smsProvider("SMSGATE");
        Provider healthy = smsProvider("SMPP");
        disabled.disable();
        down.markHealth(ProviderHealthStatus.DOWN);
        ChannelConfig channel = smsChannel(BalancingStrategy.PRIMARY_ONLY, List.of(disabled, down, healthy));

        // Act
        List<ProviderRef> chain = fallbackChain.providerChain(channel, List.of(disabled, down, healthy));

        // Assert
        assertThat(chain).containsExactly(healthy.ref());
    }

    @Test
    @DisplayName("FR-2.2: the next provider after a failure is the following one in the chain")
    void nextProviderFollowsTheChain() {
        // Arrange
        Provider primary = smsProvider("PLAYMOBILE");
        Provider reserve = smsProvider("SMSGATE");
        List<ProviderRef> chain = List.of(primary.ref(), reserve.ref());

        // Act + Assert
        assertThat(fallbackChain.nextProvider(chain, primary.ref())).contains(reserve.ref());
        assertThat(fallbackChain.nextProvider(chain, reserve.ref())).isEmpty();
        assertThat(fallbackChain.nextProvider(chain, smsProvider("UNKNOWN").ref()))
                .isEmpty();
    }

    @Test
    @DisplayName("PR-01: the first untried provider is picked on retry")
    void nextUntriedSkipsExhaustedProviders() {
        // Arrange
        Provider primary = smsProvider("PLAYMOBILE");
        Provider reserve = smsProvider("SMSGATE");
        List<ProviderRef> chain = List.of(primary.ref(), reserve.ref());

        // Act + Assert
        assertThat(fallbackChain.nextUntried(chain, Set.of())).contains(primary.ref());
        assertThat(fallbackChain.nextUntried(chain, Set.of(primary.ref()))).contains(reserve.ref());
        assertThat(fallbackChain.nextUntried(chain, Set.of(primary.ref(), reserve.ref())))
                .isEmpty();
    }

    @Test
    @DisplayName("MP-03: the cross-channel chain moves PUSH → SMS → EMAIL")
    void crossChannelFallback() {
        // Arrange
        ChannelPlan plan = ChannelPlan.fallbackChain(Channel.PUSH, Channel.SMS, Channel.EMAIL);

        // Act + Assert
        assertThat(fallbackChain.nextChannel(plan, Channel.PUSH)).contains(Channel.SMS);
        assertThat(fallbackChain.nextChannel(plan, Channel.EMAIL)).isEmpty();
        assertThat(fallbackChain.nextChannel(ChannelPlan.explicitChannel(Channel.SMS), Channel.SMS))
                .isEmpty();
    }

    @Test
    @DisplayName("null arguments are refused")
    void argumentsAreChecked() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> fallbackChain.providerChain(null, List.of()));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> fallbackChain.nextProvider(
                        null, smsProvider("PLAYMOBILE").ref()));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> fallbackChain.nextUntried(List.of(), null));
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> fallbackChain.nextChannel(null, Channel.SMS));
    }
}
