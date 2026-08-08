package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ChannelSelectionMode;

/** The three channel-selection modes (MP-03, FR-8.1). */
class ChannelPlanTest {

    @Test
    @DisplayName("an explicit plan names exactly one channel")
    void explicitPlanCarriesOneChannel() {
        // Act
        ChannelPlan plan = ChannelPlan.explicitChannel(Channel.SMS);

        // Assert
        assertThat(plan.mode()).isEqualTo(ChannelSelectionMode.EXPLICIT);
        assertThat(plan.primaryChannel()).contains(Channel.SMS);
        assertThat(plan.allows(Channel.SMS)).isTrue();
        assertThat(plan.allows(Channel.EMAIL)).isFalse();
        assertThat(plan.hasCrossChannelFallback()).isFalse();
        assertThat(plan.nextAfter(Channel.SMS)).isEmpty();
    }

    @Test
    @DisplayName("module choice without candidates allows every channel")
    void moduleChoiceAllowsEveryChannel() {
        // Act
        ChannelPlan plan = ChannelPlan.moduleChoice();

        // Assert
        assertThat(plan.channels()).isEmpty();
        assertThat(plan.primaryChannel()).isEmpty();
        assertThat(plan.allows(Channel.PUSH)).isTrue();
        assertThat(ChannelPlan.moduleChoice(List.of(Channel.SMS, Channel.EMAIL)).allows(Channel.PUSH))
                .isFalse();
    }

    @Test
    @DisplayName("a fallback chain walks the channels in order")
    void fallbackChainWalksInOrder() {
        // Act
        ChannelPlan plan = ChannelPlan.fallbackChain(Channel.PUSH, Channel.SMS, Channel.EMAIL);

        // Assert
        assertThat(plan.primaryChannel()).contains(Channel.PUSH);
        assertThat(plan.nextAfter(Channel.PUSH)).contains(Channel.SMS);
        assertThat(plan.nextAfter(Channel.SMS)).contains(Channel.EMAIL);
        assertThat(plan.nextAfter(Channel.EMAIL)).isEmpty();
        assertThat(plan.hasCrossChannelFallback()).isTrue();
    }

    @Test
    @DisplayName("invalid plans are rejected")
    void rejectsInvalidPlans() {
        // Act + Assert
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> new ChannelPlan(ChannelSelectionMode.EXPLICIT, List.of(Channel.SMS, Channel.EMAIL)))
                .withMessageContaining("exactly one channel");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> ChannelPlan.fallbackChain(Channel.SMS))
                .withMessageContaining("at least two channels");
        assertThatExceptionOfType(DomainValidationException.class)
                .isThrownBy(() -> ChannelPlan.fallbackChain(Channel.SMS, Channel.SMS))
                .withMessageContaining("distinct");
        assertThatExceptionOfType(DomainValidationException.class).isThrownBy(() -> new ChannelPlan(null, List.of()));
    }
}
