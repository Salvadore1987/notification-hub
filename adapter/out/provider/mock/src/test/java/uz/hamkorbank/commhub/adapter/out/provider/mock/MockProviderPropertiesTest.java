package uz.hamkorbank.commhub.adapter.out.provider.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The switch of the fake provider, whose default is the whole of its safety (ADR-0041). */
class MockProviderPropertiesTest {

    @Test
    @DisplayName("the fake provider is off unless a deployment says otherwise")
    void isOffByDefault() {
        // Arrange + Act
        MockProviderProperties defaults = MockProviderProperties.defaults();

        // Assert — единственное, что отделяет боевой контур от «отправили никуда и отчитались успехом»
        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.reportDelay()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("a negative delay is a configuration mistake, not a fast provider")
    void refusesNegativeDelays() {
        // Arrange + Act + Assert
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockProviderProperties(true, Duration.ofMillis(-1), null));
    }
}
