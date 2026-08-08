package uz.hamkorbank.commhub.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.STREAM_ID;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.recipient;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.routingConfiguration;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.stream;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.application.dto.RouteEvaluationView;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.mapper.ConfigMapperImpl;
import uz.hamkorbank.commhub.application.port.in.query.RouteEvaluationQuery;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.application.service.support.MessageRouting;
import uz.hamkorbank.commhub.application.service.support.RoutingRotation;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ProviderHealthStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.service.FallbackChain;
import uz.hamkorbank.commhub.domain.service.Router;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;

/** Dry run of the routing configuration: "which route would message X get?" (FR-8.9). */
class RouteEvaluationServiceTest {

    private ProviderConfigRepository configuration;
    private StreamRepository streams;
    private RouteEvaluationService service;

    @BeforeEach
    void setUp() {
        configuration = mock(ProviderConfigRepository.class);
        streams = mock(StreamRepository.class);
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        when(streams.findById(STREAM_ID)).thenReturn(Optional.of(stream()));
        MessageRouting routing = new MessageRouting(
                new Router(new FallbackChain()), new SegmentCalculator(), configuration, new RoutingRotation());
        service = new RouteEvaluationService(streams, routing, clock, new ConfigMapperImpl());
    }

    @Test
    @DisplayName("FR-8.9: the dry run names the provider, its reserves and the expected cost")
    void evaluatesTheRouteWithoutSending() {
        // Arrange
        Provider primary = smsProvider("PLAYMOBILE");
        Provider reserve = smsProvider("SMSGATE");
        when(configuration.routingConfiguration(STREAM_ID)).thenReturn(routingConfiguration(List.of(primary, reserve)));

        // Act
        RouteEvaluationView view = service.evaluate(new RouteEvaluationQuery(
                STREAM_ID, recipient(), Channel.SMS, TrafficClass.CRITICAL_OTP, null, "Kod: 123456"));

        // Assert
        assertThat(view.routed()).isTrue();
        assertThat(view.channel()).isEqualTo(Channel.SMS);
        assertThat(view.provider()).isEqualTo(primary.code());
        assertThat(view.fallbackProviders()).containsExactly(reserve.code());
        assertThat(view.segments()).isEqualTo(1);
        assertThat(view.estimatedCostOptional()).isPresent();
        assertThat(view.rejectionOptional()).isEmpty();
    }

    @Test
    @DisplayName("FR-8.9: the dry run reports the same rejection a real submission would get")
    void reportsWhyThereIsNoRoute() {
        // Arrange
        Provider only = smsProvider("PLAYMOBILE");
        only.markHealth(ProviderHealthStatus.DOWN, NOW);
        when(configuration.routingConfiguration(STREAM_ID)).thenReturn(routingConfiguration(List.of(only)));

        // Act
        RouteEvaluationView view =
                service.evaluate(new RouteEvaluationQuery(STREAM_ID, recipient(), null, null, null, null));

        // Assert
        assertThat(view.routed()).isFalse();
        assertThat(view.rejectionOptional()).isPresent().get().satisfies(rejection -> assertThat(rejection.reason())
                .isEqualTo(RejectionReason.NO_ROUTE_AVAILABLE));
    }

    @Test
    @DisplayName("a longer text raises the segment count least-cost routing decides on (§18.3)")
    void countsSegmentsOfTheGivenText() {
        // Arrange
        when(configuration.routingConfiguration(STREAM_ID))
                .thenReturn(routingConfiguration(List.of(smsProvider("PLAYMOBILE"))));

        // Act
        RouteEvaluationView view = service.evaluate(
                new RouteEvaluationQuery(STREAM_ID, recipient(), Channel.SMS, null, null, "a".repeat(200)));

        // Assert
        assertThat(view.segments()).isEqualTo(2);
    }

    @Test
    @DisplayName("a dry run against an unregistered stream is a 404")
    void unknownStreamIsNotFound() {
        // Arrange
        when(streams.findById(StreamId.of("ghost"))).thenReturn(Optional.empty());

        // Act + Assert
        assertThatExceptionOfType(NotFoundException.class)
                .isThrownBy(() -> service.evaluate(
                        new RouteEvaluationQuery(StreamId.of("ghost"), recipient(), null, null, null, null)));
        assertThat(configuration).isNotNull();
        verifyNothingWasRouted();
    }

    private void verifyNothingWasRouted() {
        org.mockito.Mockito.verify(configuration, org.mockito.Mockito.never()).routingConfiguration(any());
    }
}
