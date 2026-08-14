package uz.hamkorbank.commhub.application.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.NOW;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.androidToken;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.iosToken;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushMessage;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushProvider;
import static uz.hamkorbank.commhub.application.ApplicationFixtures.pushRecipient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import uz.hamkorbank.commhub.application.mapper.ProviderSubmissionMapperImpl;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.PushDelivery;
import uz.hamkorbank.commhub.application.port.out.PushDeliveryLogPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.PushProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.AttemptId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;

/** Fan-out of one notification over every device of a recipient (PU-04, PU-09, PU-10). */
class PushFanOutTest {

    private static final Provider FCM = pushProvider("FCM", "fcm-http");

    private PushTokenRegistrar tokens;
    private PushDeliveryLogPort deliveries;
    private ClockPort clock;

    @BeforeEach
    void setUp() {
        tokens = mock(PushTokenRegistrar.class);
        deliveries = mock(PushDeliveryLogPort.class);
        clock = mock(ClockPort.class);
        when(clock.now()).thenReturn(NOW);
    }

    @Test
    @DisplayName("PU-09: every active device of the recipient receives the notification")
    void deliversToEveryDevice() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter(PushPlatform.ANDROID);
        Message message = pushMessage(pushRecipient(androidToken("device-a"), androidToken("device-b")));
        PushFanOut fanOut = fanOut(adapter);

        // Act
        ProviderAck ack = fanOut.submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(adapter.tokensCalled()).containsExactlyInAnyOrder("device-a", "device-b");
    }

    @Test
    @DisplayName("PU-09: one device accepting makes the message accepted — the customer was notified")
    void acceptsWhenAnyDeviceAccepts() {
        // Arrange — the tablet's token is dead, the phone's is not
        RecordingAdapter adapter = new RecordingAdapter(
                PushPlatform.ANDROID,
                Map.of("dead", ProviderAck.rejected("UNREGISTERED", "gone", NOW).withInvalidRecipient()));
        Message message = pushMessage(pushRecipient(androidToken("dead"), androidToken("alive")));

        // Act
        ProviderAck ack = fanOut(adapter).submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(ack.invalidRecipient()).isFalse();
    }

    @Test
    @DisplayName("PU-09: with no device accepted, the strongest instruction to the saga wins")
    void aggregatesTheWorstFailure() {
        // Arrange — a permanent refusal on one device, a provider-wide failure on the other
        RecordingAdapter adapter = new RecordingAdapter(
                PushPlatform.ANDROID,
                Map.of(
                        "bad-payload", ProviderAck.rejected("INVALID_ARGUMENT", "payload", NOW),
                        "auth", ProviderAck.failed("401", ErrorClass.BLOCKING, "credentials", NOW)));
        Message message = pushMessage(pushRecipient(androidToken("bad-payload"), androidToken("auth")));

        // Act
        ProviderAck ack = fanOut(adapter).submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(ack.isBlocking()).isTrue();
        assertThat(ack.errorDescription()).contains("payload").contains("credentials");
    }

    @Test
    @DisplayName("PU-04: a device the platform already retired is not called again")
    void skipsRetiredDevices() {
        // Arrange
        PushToken retired = androidToken("retired");
        when(tokens.isRetired(eq(retired), any())).thenReturn(true);
        RecordingAdapter adapter = new RecordingAdapter(PushPlatform.ANDROID);
        Message message = pushMessage(pushRecipient(retired, androidToken("alive")));

        // Act
        fanOut(adapter).submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(adapter.tokensCalled()).containsExactly("alive");
    }

    @Test
    @DisplayName("PU-04: a device the platform declares dead is retired once, from its own answer")
    void retiresAnInvalidatedDevice() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter(
                PushPlatform.ANDROID,
                Map.of(
                        "dead",
                        ProviderAck.rejected("UNREGISTERED", "app uninstalled", NOW)
                                .withInvalidRecipient()));
        Message message = pushMessage(pushRecipient(androidToken("dead"), androidToken("alive")));

        // Act
        fanOut(adapter).submit(message, FCM.ref(), attempt(message));

        // Assert
        verify(tokens).invalidate(eq(message), eq(FCM.ref()), eq(androidToken("dead")), any(), eq(NOW));
        verify(tokens, never()).invalidate(any(), any(), eq(androidToken("alive")), any(), any());
    }

    @Test
    @DisplayName("PU-09: every device gets its own row, with the platform and the attempt it belonged to")
    void recordsOneRowPerDevice() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter(PushPlatform.ANDROID);
        Message message = pushMessage(pushRecipient(androidToken("device-a"), androidToken("device-b")));
        DeliveryAttempt attempt = attempt(message);

        // Act
        fanOut(adapter).submit(message, FCM.ref(), attempt);

        // Assert
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PushDelivery>> rows = ArgumentCaptor.forClass(List.class);
        verify(deliveries).record(rows.capture());
        assertThat(rows.getValue()).hasSize(2);
        assertThat(rows.getValue()).allSatisfy(row -> {
            assertThat(row.attemptId()).isEqualTo(attempt.id());
            assertThat(row.platform()).isEqualTo(PushPlatform.ANDROID);
            assertThat(row.outcome().result()).isEqualTo(AttemptResult.ACCEPTED);
        });
    }

    @Test
    @DisplayName("§9.4: a token whose platform the routed adapter does not serve is not sent to it")
    void ignoresDevicesOfAnotherPlatform() {
        // Arrange — FCM without the PU-05 mode: the iOS device belongs to the APNs route
        RecordingAdapter adapter = new RecordingAdapter(PushPlatform.ANDROID);
        Message message = pushMessage(pushRecipient(iosToken("iphone"), androidToken("pixel")));

        // Act
        fanOut(adapter).submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(adapter.tokensCalled()).containsExactly("pixel");
    }

    @Test
    @DisplayName("PU-09: a recipient with no addressable device is refused permanently, not retried")
    void refusesWhenNoDeviceIsAddressable() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter(PushPlatform.ANDROID);
        Message message = pushMessage(pushRecipient(iosToken("iphone")));

        // Act
        ProviderAck ack = fanOut(adapter).submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(ack.responseCode()).isEqualTo(PushFanOut.NO_DEVICE_CODE);
        assertThat(ack.isRetryable()).isFalse();
    }

    @Test
    @DisplayName("PR-01: an adapter that throws costs its own device, not the whole fan-out")
    void oneFailingDeviceDoesNotStopTheOthers() {
        // Arrange
        RecordingAdapter adapter = new RecordingAdapter(PushPlatform.ANDROID, Map.of(), "boom");
        Message message = pushMessage(pushRecipient(androidToken("boom"), androidToken("alive")));

        // Act
        ProviderAck ack = fanOut(adapter).submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(adapter.tokensCalled()).containsExactlyInAnyOrder("boom", "alive");
    }

    @Test
    @DisplayName("AR-04: a route with no push adapter deployed fails non-retryably instead of throwing")
    void reportsMissingAdapter() {
        // Arrange
        Message message = pushMessage(pushRecipient(androidToken("device")));
        PushFanOut fanOut = new PushFanOut(
                ports(List.of()),
                new ProviderSubmissionMapperImpl(),
                tokens,
                new PushDeliveryJournal(tokens, deliveries),
                clock);

        // Act
        ProviderAck ack = fanOut.submit(message, FCM.ref(), attempt(message));

        // Assert
        assertThat(ack.errorClass()).isEqualTo(ErrorClass.NON_RETRYABLE);
        assertThat(ack.responseCode()).isEqualTo(PushFanOut.NO_ADAPTER_CODE);
        assertThat(fanOut.supports(FCM.ref())).isFalse();
    }

    private PushFanOut fanOut(PushProviderPort adapter) {
        return new PushFanOut(
                ports(List.of(adapter)),
                new ProviderSubmissionMapperImpl(),
                tokens,
                new PushDeliveryJournal(tokens, deliveries),
                clock);
    }

    private static DeliveryAttempt attempt(Message message) {
        return DeliveryAttempt.start(AttemptId.newId(), message.id(), FCM.ref(), 1, null, NOW);
    }

    /** A push adapter that records what it was asked to deliver and answers per token. */
    private static final class RecordingAdapter implements PushProviderPort {

        private final PushPlatform platform;
        private final Map<String, ProviderAck> answers;
        private final String throwingToken;
        private final List<String> calls = new ArrayList<>(ConcurrentHashMap.newKeySet());

        private RecordingAdapter(PushPlatform platform) {
            this(platform, Map.of(), null);
        }

        private RecordingAdapter(PushPlatform platform, Map<String, ProviderAck> answers) {
            this(platform, answers, null);
        }

        private RecordingAdapter(PushPlatform platform, Map<String, ProviderAck> answers, String throwingToken) {
            this.platform = platform;
            this.answers = answers;
            this.throwingToken = throwingToken;
        }

        @Override
        public AdapterType adapterType() {
            return FCM.adapterType();
        }

        @Override
        public boolean supportsPlatform(PushPlatform candidate) {
            return candidate == platform;
        }

        @Override
        public synchronized ProviderAck submit(PushSubmission submission) {
            String token = submission.token().value();
            calls.add(token);
            if (token.equals(throwingToken)) {
                throw new IllegalStateException("connection reset");
            }
            return answers.getOrDefault(token, ProviderAck.accepted(ProviderMessageId.of("fcm-1"), "200", NOW));
        }

        private synchronized List<String> tokensCalled() {
            return List.copyOf(calls);
        }
    }

    /** Minimal {@code ObjectProvider} over a fixed list; the fan-out only ever streams it. */
    private static <T> ObjectProvider<T> ports(List<T> values) {
        return new ObjectProvider<>() {
            @Override
            public Stream<T> stream() {
                return values.stream();
            }

            @Override
            public T getObject() {
                throw new UnsupportedOperationException("not used by the fan-out");
            }

            @Override
            public T getObject(Object... args) {
                throw new UnsupportedOperationException("not used by the fan-out");
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
}
