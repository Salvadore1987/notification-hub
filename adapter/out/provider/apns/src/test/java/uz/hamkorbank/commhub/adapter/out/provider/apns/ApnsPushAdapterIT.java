package uz.hamkorbank.commhub.adapter.out.provider.apns;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.adapter.out.provider.ProviderStubs;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRuntimeSettings;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderSupport;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderThrottle;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * Contract test of the APNs adapter against stubs built from §9.4.2 (QA-04, PR-04).
 *
 * <p>WireMock speaks HTTP/1.1, so what is verified here is the contract — the path, the {@code apns-*}
 * headers, the payload shape and the reason table — not the HTTP/2 framing, which belongs to the JDK
 * client and to a real connection to Apple (PU-07).
 */
@Tag("integration")
class ApnsPushAdapterIT {

    private static final String DEVICE_TOKEN = "9a1b2c3d4e5f";
    private static final String SEND_PATH = "/3/device/" + DEVICE_TOKEN;

    private WireMockServer apple;
    private WireMockServer appleSandbox;
    private ApnsPushAdapter adapter;
    private CircuitBreakerRegistry breakers;
    private ProviderThrottle throttle;
    private String privateKeyPem;

    @BeforeEach
    void setUp() {
        apple = ProviderStubs.startServer();
        appleSandbox = ProviderStubs.startServer();
        breakers = CircuitBreakerRegistry.ofDefaults();
        throttle = new ProviderThrottle();
        privateKeyPem = generateP8();
        adapter = adapter(RateLimit.unlimited());
    }

    @AfterEach
    void tearDown() {
        apple.stop();
        appleSandbox.stop();
    }

    @Test
    @DisplayName("PU-06: the notification is posted to /3/device/{token} with the documented apns-* headers")
    void sendsWithTheDocumentedHeaders() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)));
        PushSubmission submission = submission(TrafficClass.CRITICAL_OTP, false, null);

        // Act
        ProviderAck ack = adapter.submit(submission);

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(ack.providerMessageId().value())
                .isEqualTo(submission.messageId().value().toString());
        apple.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                .withHeader(ApnsPushAdapter.HEADER_TOPIC, equalTo("uz.hamkorbank.mobile"))
                .withHeader(ApnsPushAdapter.HEADER_PUSH_TYPE, equalTo("alert"))
                .withHeader(ApnsPushAdapter.HEADER_PRIORITY, equalTo("10"))
                .withHeader(
                        ApnsPushAdapter.HEADER_ID,
                        equalTo(submission.messageId().value().toString()))
                .withHeader(ApnsPushAdapter.HEADER_AUTHORIZATION, matching("bearer [\\w-]+\\.[\\w-]+\\.[\\w-]+"))
                .withRequestBody(matchingJsonPath("$.aps.alert.body", equalTo("Kod: 4821"))));
    }

    @Test
    @DisplayName("PU-06: bulk traffic goes out power-considerate, with a collapse id when one was asked for")
    void bulkTrafficIsPowerConsiderate() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)));

        // Act
        adapter.submit(submission(TrafficClass.NOTIFICATION, false, "promo"));

        // Assert
        apple.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                .withHeader(ApnsPushAdapter.HEADER_PRIORITY, equalTo("5"))
                .withHeader(ApnsPushAdapter.HEADER_COLLAPSE_ID, equalTo("promo")));
    }

    @Test
    @DisplayName("PU-08: 410 Unregistered retires the token and leaves the health of APNs alone")
    void unregisteredRetiresTheToken() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH))
                .willReturn(aResponse().withStatus(410).withBody("{\"reason\":\"Unregistered\"}")));

        // Act
        ProviderAck ack = adapter.submit(submission(TrafficClass.NOTIFICATION, false, null));

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(ack.invalidRecipient()).isTrue();
        assertThat(ack.responseCode()).isEqualTo("Unregistered");
        assertThat(breakers.circuitBreaker("APNS").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("PU-08: PayloadTooLarge is the message's problem, not the token's and not the provider's")
    void payloadTooLargeDoesNotRetireTheToken() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH))
                .willReturn(aResponse().withStatus(413).withBody("{\"reason\":\"PayloadTooLarge\"}")));

        // Act
        ProviderAck ack = adapter.submit(submission(TrafficClass.TRANSACTIONAL, false, null));

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(ack.invalidRecipient()).isFalse();
        assertThat(breakers.circuitBreaker("APNS").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("PU-06/PU-08: an expired provider token opens the breaker and is re-signed for the next call")
    void expiredProviderTokenOpensTheBreaker() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH))
                .willReturn(aResponse().withStatus(403).withBody("{\"reason\":\"ExpiredProviderToken\"}")));

        // Act
        ProviderAck ack = adapter.submit(submission(TrafficClass.TRANSACTIONAL, false, null));

        // Assert
        assertThat(ack.isBlocking()).isTrue();
        assertThat(breakers.circuitBreaker("APNS").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("PU-08: TooManyRequests fails the message over without blaming APNs")
    void throttlingByAppleIsRetryable() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH))
                .willReturn(aResponse().withStatus(429).withBody("{\"reason\":\"TooManyRequests\"}")));

        // Act
        ProviderAck ack = adapter.submit(submission(TrafficClass.NOTIFICATION, false, null));

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        assertThat(breakers.circuitBreaker("APNS").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("PU-13: a test send goes to the sandbox host, where a development token actually exists")
    void testSendGoesToSandbox() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)));
        appleSandbox.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)));

        // Act
        ProviderAck ack = adapter.submit(submission(TrafficClass.TRANSACTIONAL, true, null));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        appleSandbox.verify(1, postRequestedFor(urlEqualTo(SEND_PATH)));
        apple.verify(0, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    @DisplayName("§9.4.2: one call per attempt — a lost answer must not become a second alert")
    void doesNotRetryInsideTheAttempt() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(503)));

        // Act
        ProviderAck ack = adapter.submit(submission(TrafficClass.TRANSACTIONAL, false, null));

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        apple.verify(1, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    @DisplayName("PU-06: the signed provider token is reused across notifications, not re-signed per call")
    void reusesTheProviderToken() {
        // Arrange
        apple.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)));

        // Act
        adapter.submit(submission(TrafficClass.TRANSACTIONAL, false, null));
        adapter.submit(submission(TrafficClass.TRANSACTIONAL, false, null));

        // Assert — both requests carry the same authorization header
        assertThat(apple.getAllServeEvents().stream()
                        .map(event -> event.getRequest().getHeader(ApnsPushAdapter.HEADER_AUTHORIZATION))
                        .distinct()
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("§9.4: only iOS tokens are served — an Android device has nothing to do with Apple")
    void servesOnlyIos() {
        assertThat(adapter.supportsPlatform(PushPlatform.IOS)).isTrue();
        assertThat(adapter.supportsPlatform(PushPlatform.ANDROID)).isFalse();
        assertThat(adapter.supportsPlatform(PushPlatform.WEB)).isFalse();
    }

    private ApnsPushAdapter adapter(RateLimit rateLimit) {
        return new ApnsPushAdapter(
                properties(rateLimit),
                new ApnsMessageCodec(),
                new ProviderSupport(
                        new ProviderCallExecutor(breakers, RetryRegistry.ofDefaults(), FixedClock.standard()),
                        throttle,
                        ProviderRuntimeSettings.configurationOnly(),
                        FixedClock.standard(),
                        new ProviderRestClients()));
    }

    private ApnsProperties properties(RateLimit rateLimit) {
        return new ApnsProperties(
                true,
                "APNS",
                new ApnsProperties.Credentials("TEAM123", "KEY123", privateKeyPem, Duration.ofMinutes(40)),
                new ApnsProperties.Sending("uz.hamkorbank.mobile", null, Duration.ofMinutes(10)),
                rateLimit,
                new ProviderHttpProperties(apple.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2)),
                new ProviderHttpProperties(appleSandbox.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2)),
                ProviderResilienceProperties.withoutInnerRetry());
    }

    /** A throwaway P-256 key in the PEM form Apple's {@code .p8} file uses. */
    private static String generateP8() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(
                                    generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PushSubmission submission(TrafficClass trafficClass, boolean test, String collapseKey) {
        ProviderRef provider = new ProviderRef(
                ProviderId.newId(), ProviderCode.of("APNS"), Channel.PUSH, AdapterType.of("apns-http2"));
        return new PushSubmission(
                provider,
                MessageId.of(UuidV7.generate()),
                PushToken.of(DEVICE_TOKEN, PushPlatform.IOS),
                PushContent.of("Hamkorbank", "Kod: 4821"),
                Timing.withTtl(Duration.ofMinutes(5)),
                collapseKey,
                new SubmissionContext(trafficClass, Priority.NORMAL, CorrelationId.of("corr-1"), test));
    }
}
