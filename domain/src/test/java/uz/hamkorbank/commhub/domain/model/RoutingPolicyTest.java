package uz.hamkorbank.commhub.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.hamkorbank.commhub.domain.DomainFixtures.STREAM_ID;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/** Declarative routing rules (§10.1 routing_policy, FR-8.9). */
class RoutingPolicyTest {

    @Test
    @DisplayName("an empty match applies to every message")
    void emptyMatchAppliesToEverything() {
        // Arrange
        RoutingPolicy policy = RoutingPolicy.of(
                RoutingPolicyId.newId(), RoutingPolicy.Match.any(), RoutingPolicy.Action.toChannel(Channel.SMS), 0);

        // Act + Assert
        assertThat(policy.matches(STREAM_ID, TrafficClass.NOTIFICATION, Priority.LOW, Channel.SMS))
                .isTrue();
        assertThat(policy.priority()).isZero();
        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.action().channelOptional()).contains(Channel.SMS);
        assertThat(policy.action().balancingStrategyOptional()).isEmpty();
    }

    @Test
    @DisplayName("a stream and traffic-class match narrows the rule down")
    void matchNarrowsByStreamAndClass() {
        // Arrange
        RoutingPolicy policy = RoutingPolicy.of(
                RoutingPolicyId.newId(),
                new RoutingPolicy.Match(STREAM_ID, TrafficClass.CRITICAL_OTP, Priority.HIGH, Channel.SMS),
                RoutingPolicy.Action.toProviders(Channel.SMS, List.of(ProviderCode.of("PLAYMOBILE"))),
                100);

        // Act + Assert
        assertThat(policy.matches(STREAM_ID, TrafficClass.CRITICAL_OTP, Priority.REALTIME, Channel.SMS))
                .isTrue();
        assertThat(policy.matches(StreamId.of("crm"), TrafficClass.CRITICAL_OTP, Priority.REALTIME, Channel.SMS))
                .isFalse();
        assertThat(policy.matches(STREAM_ID, TrafficClass.NOTIFICATION, Priority.REALTIME, Channel.SMS))
                .isFalse();
        assertThat(policy.matches(STREAM_ID, TrafficClass.CRITICAL_OTP, Priority.NORMAL, Channel.SMS))
                .isFalse();
        assertThat(policy.matches(STREAM_ID, TrafficClass.CRITICAL_OTP, Priority.REALTIME, Channel.EMAIL))
                .isFalse();
        assertThat(policy.matches(STREAM_ID, TrafficClass.CRITICAL_OTP, null, Channel.SMS))
                .isFalse();
        assertThat(policy.action().providerOrder()).containsExactly(ProviderCode.of("PLAYMOBILE"));
    }

    @Test
    @DisplayName("a disabled rule never matches")
    void disabledPolicyNeverMatches() {
        // Arrange
        RoutingPolicy policy = RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.ofStream(STREAM_ID),
                new RoutingPolicy.Action(Channel.SMS, List.of(), BalancingStrategy.LEAST_COST),
                10);

        // Act
        policy.disable();

        // Assert
        assertThat(policy.matches(STREAM_ID, TrafficClass.NOTIFICATION, Priority.LOW, Channel.SMS))
                .isFalse();

        policy.enable();
        assertThat(policy.matches(STREAM_ID, TrafficClass.NOTIFICATION, Priority.LOW, Channel.SMS))
                .isTrue();
        assertThat(policy.action().balancingStrategyOptional()).contains(BalancingStrategy.LEAST_COST);
    }

    @Test
    @DisplayName("a traffic-class match ignores stream, priority and channel")
    void trafficClassOnlyMatch() {
        // Arrange
        RoutingPolicy policy = RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.ofTrafficClass(TrafficClass.CRITICAL_OTP),
                RoutingPolicy.Action.toChannel(Channel.SMS),
                50);

        // Act + Assert
        assertThat(policy.matches(StreamId.of("any-stream"), TrafficClass.CRITICAL_OTP, null, null))
                .isTrue();
        assertThat(policy.match().trafficClass()).isEqualTo(TrafficClass.CRITICAL_OTP);
    }
}
