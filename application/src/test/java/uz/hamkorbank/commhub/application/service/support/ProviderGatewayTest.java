package uz.hamkorbank.commhub.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsMessage;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.smsProvider;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import uz.hamkorbank.commhub.application.mapper.ProviderSubmissionMapperImpl;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.PushDeliveryLogPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.SmsProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/** Resolution of a routed provider onto its adapter (MP-05, AR-04, PR-01). */
class ProviderGatewayTest {

    private RecordingSmsAdapter playmobileAdapter;
    private ProviderGateway gateway;
    private Provider playmobile;
    private Provider smsgate;

    @BeforeEach
    void setUp() {
        playmobile = smsProvider("PLAYMOBILE");
        smsgate = smsProvider("SMSGATE");
        playmobileAdapter = new RecordingSmsAdapter(playmobile.adapterType());
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        gateway = new ProviderGateway(
                ports(playmobileAdapter),
                ports(),
                pushFanOut(clock),
                new ProviderSubmissionMapperImpl(),
                new ProviderMessageIdFactory(),
                mock(MetricsPort.class),
                clock);
    }

    @Test
    @DisplayName("AR-04: the adapter whose adapterType matches the route receives the submission")
    void routesToMatchingAdapter() {
        // Arrange
        Message message = smsMessage();
        ProviderMessageId providerMessageId = gateway.providerMessageIdFor(message);

        // Act
        ProviderAck ack =
                gateway.submit(message, playmobile.ref(), attempt(message, playmobile.ref(), providerMessageId), null);

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(playmobileAdapter.received).isNotNull();
        assertThat(playmobileAdapter.received.recipient())
                .isEqualTo(message.recipient().msisdn());
        assertThat(playmobileAdapter.received.context().trafficClass())
                .isEqualTo(message.envelope().trafficClass());
    }

    @Test
    @DisplayName("AR-04: a route without a deployed adapter fails non-retryably instead of throwing")
    void reportsMissingAdapter() {
        // Arrange
        Message message = smsMessage();

        // Act
        ProviderAck ack = gateway.submit(message, smsgate.ref(), attempt(message, smsgate.ref(), null), null);

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.ERROR);
        assertThat(ack.errorClass()).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(gateway.supports(smsgate.ref())).isFalse();
        assertThat(gateway.supports(playmobile.ref())).isTrue();
    }

    @Test
    @DisplayName("PR-01: an adapter that throws is turned into a retryable acknowledgement")
    void turnsAdapterFailureIntoRetryableAck() {
        // Arrange
        ClockPort clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
        ProviderGateway failing = new ProviderGateway(
                ports(new ThrowingSmsAdapter(playmobile.adapterType())),
                ports(),
                pushFanOut(clock),
                new ProviderSubmissionMapperImpl(),
                new ProviderMessageIdFactory(),
                mock(MetricsPort.class),
                clock);

        // Act
        Message message = smsMessage();
        ProviderAck ack = failing.submit(message, playmobile.ref(), attempt(message, playmobile.ref(), null), null);

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        assertThat(ack.respondedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("§9.1: the generated provider message id fits the 20-character Playmobile limit")
    void generatesProviderMessageId() {
        // Arrange + Act
        ProviderMessageId id = gateway.providerMessageIdFor(smsMessage());

        // Assert
        assertThat(id.value()).hasSizeLessThanOrEqualTo(ProviderMessageIdFactory.MAX_LENGTH);
    }

    /** A fan-out with no push adapters deployed; this test is about the SMS branch of the gateway. */
    private static PushFanOut pushFanOut(ClockPort clock) {
        return new PushFanOut(
                ports(),
                new ProviderSubmissionMapperImpl(),
                mock(PushTokenRegistrar.class),
                mock(PushDeliveryLogPort.class),
                clock);
    }

    private static DeliveryAttempt attempt(Message message, ProviderRef provider, ProviderMessageId providerMessageId) {
        return DeliveryAttempt.start(AttemptId.newId(), message.id(), provider, 1, providerMessageId, NOW);
    }

    private static <T> ObjectProvider<T> ports(T adapter) {
        return ports(List.of(adapter));
    }

    private static <T> ObjectProvider<T> ports() {
        return ports(List.of());
    }

    /** Minimal {@code ObjectProvider} over a fixed list; the gateway only ever streams it. */
    private static <T> ObjectProvider<T> ports(List<T> values) {
        return new ObjectProvider<>() {
            @Override
            public Stream<T> stream() {
                return values.stream();
            }

            @Override
            public T getObject() {
                throw new UnsupportedOperationException("not used by the gateway");
            }

            @Override
            public T getObject(Object... args) {
                throw new UnsupportedOperationException("not used by the gateway");
            }

            @Override
            public T getIfAvailable() {
                return values.isEmpty() ? null : values.getFirst();
            }

            @Override
            public T getIfUnique() {
                return values.size() == 1 ? values.getFirst() : null;
            }
        };
    }

    /** Test double standing in for a provider adapter (Phase 7 delivers the real ones). */
    private static final class RecordingSmsAdapter implements SmsProviderPort {

        private final AdapterType adapterType;
        private SmsSubmission received;

        private RecordingSmsAdapter(AdapterType adapterType) {
            this.adapterType = adapterType;
        }

        @Override
        public AdapterType adapterType() {
            return adapterType;
        }

        @Override
        public ProviderAck submit(SmsSubmission submission) {
            this.received = submission;
            return ProviderAck.accepted(ProviderMessageId.of("PM-1"), "0", NOW);
        }
    }

    private static final class ThrowingSmsAdapter implements SmsProviderPort {

        private final AdapterType adapterType;

        private ThrowingSmsAdapter(AdapterType adapterType) {
            this.adapterType = adapterType;
        }

        @Override
        public AdapterType adapterType() {
            return adapterType;
        }

        @Override
        public ProviderAck submit(SmsSubmission submission) {
            throw new IllegalStateException("connection reset");
        }
    }
}
