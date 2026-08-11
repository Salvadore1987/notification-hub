package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
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
 * Contract test of the FCM adapter against stubs built from §9.4.1 (QA-04, PR-04).
 *
 * <p>Both endpoints are stubbed — the OAuth2 token exchange and {@code messages:send} — because the
 * interesting behaviour spans them: the access token is obtained once and reused, and a refusal of the
 * credentials has to drop it.
 */
@Tag("integration")
class FcmPushAdapterIT {

    private static final String TOKEN_PATH = "/token";
    private static final String SEND_PATH = "/v1/projects/hamkor-mobile/messages:send";

    private WireMockServer google;
    private FcmPushAdapter adapter;
    private CircuitBreakerRegistry breakers;
    private ProviderThrottle throttle;

    @BeforeEach
    void setUp() {
        google = ProviderStubs.startServer();
        breakers = CircuitBreakerRegistry.ofDefaults();
        throttle = new ProviderThrottle();
        google.stubFor(
                post(urlEqualTo(TOKEN_PATH)).willReturn(json("{\"access_token\":\"ya29.stub\",\"expires_in\":3600}")));
        adapter = adapter(RateLimit.unlimited());
    }

    @AfterEach
    void tearDown() {
        google.stop();
    }

    @Test
    @DisplayName("PU-01/PU-03: the notification goes to messages:send of the key's project, with a bearer token")
    void sendsToTheProjectOfTheServiceAccount() {
        // Arrange
        google.stubFor(post(urlEqualTo(SEND_PATH))
                .willReturn(json("{\"name\":\"projects/hamkor-mobile/messages/0:1691481600\"}")));

        // Act
        ProviderAck ack = adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.TRANSACTIONAL, false));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(ack.providerMessageId().value()).contains("0:1691481600");
        google.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                .withHeader("Authorization", equalTo("Bearer ya29.stub"))
                .withRequestBody(matchingJsonPath("$.message.token", equalTo("device-a")))
                .withRequestBody(matchingJsonPath("$.message.android.priority", equalTo("HIGH"))));
    }

    @Test
    @DisplayName("PU-01: the access token is exchanged once and reused for the next notification")
    void reusesTheAccessToken() {
        // Arrange
        google.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(json("{\"name\":\"projects/x/messages/1\"}")));

        // Act
        adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.TRANSACTIONAL, false));
        adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.TRANSACTIONAL, false));

        // Assert
        google.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        google.verify(2, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    @DisplayName("PU-04: UNREGISTERED retires the token and never counts against the health of FCM")
    void unregisteredRetiresTheTokenWithoutBlamingTheProvider() {
        // Arrange
        google.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(json("""
                                {"error":{"code":404,"status":"NOT_FOUND","message":"Requested entity was not found.",
                                 "details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError",
                                             "errorCode":"UNREGISTERED"}]}}
                                """).withStatus(404)));

        // Act
        ProviderAck ack = adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.NOTIFICATION, false));

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(ack.invalidRecipient()).isTrue();
        assertThat(breakers.circuitBreaker("FCM").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("PU-04: QUOTA_EXCEEDED sends the message elsewhere without opening the breaker")
    void quotaExceededIsRetryableWithoutOpeningTheBreaker() {
        // Arrange
        google.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(json("""
                                {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
                                 "details":[{"errorCode":"QUOTA_EXCEEDED"}]}}
                                """).withStatus(429)));

        // Act
        ProviderAck ack = adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.NOTIFICATION, false));

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        assertThat(breakers.circuitBreaker("FCM").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("PU-01: refused credentials open the breaker and the next call fetches a new token")
    void refusedCredentialsOpenTheBreakerAndDropTheToken() {
        // Arrange
        google.stubFor(post(urlEqualTo(SEND_PATH))
                .willReturn(json("{\"error\":{\"code\":401,\"status\":\"UNAUTHENTICATED\"}}")
                        .withStatus(401)));

        // Act
        ProviderAck ack = adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.TRANSACTIONAL, false));

        // Assert
        assertThat(ack.isBlocking()).isTrue();
        assertThat(breakers.circuitBreaker("FCM").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        google.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    @Test
    @DisplayName("PU-05: with the iOS mode off the adapter declines iOS; with it on it serves both platforms")
    void iosDeliveryIsAConfigurationSwitch() {
        // Arrange + Act + Assert
        assertThat(adapter.supportsPlatform(PushPlatform.ANDROID)).isTrue();
        assertThat(adapter.supportsPlatform(PushPlatform.WEB)).isTrue();
        assertThat(adapter.supportsPlatform(PushPlatform.IOS)).isFalse();

        FcmPushAdapter both = adapter(RateLimit.unlimited(), true);
        assertThat(both.supportsPlatform(PushPlatform.IOS)).isTrue();
    }

    @Test
    @DisplayName("PU-13: a test send is validated by FCM and delivered to nobody")
    void testSendIsADryRun() {
        // Arrange
        google.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(json("{\"name\":\"projects/x/messages/1\"}")));

        // Act
        ProviderAck ack = adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.TRANSACTIONAL, true));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        google.verify(postRequestedFor(urlEqualTo(SEND_PATH))
                .withRequestBody(matchingJsonPath("$.validate_only", equalTo("true"))));
    }

    @Test
    @DisplayName("PR-01: a 503 is retried inside the attempt and then handed back as retryable")
    void serverErrorsAreRetriedInsideTheAttempt() {
        // Arrange
        google.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(503)));

        // Act
        ProviderAck ack = adapter.submit(submission(PushPlatform.ANDROID, TrafficClass.NOTIFICATION, false));

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        google.verify(2, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    @Test
    @DisplayName("FR-2.5: a throttled notification never reaches FCM — it fails over instead")
    void throttledMessageNeverReachesTheProvider() {
        // Arrange
        FcmPushAdapter limited = adapter(new RateLimit(0, 1, 0));
        google.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(json("{\"name\":\"projects/x/messages/1\"}")));

        // Act
        ProviderAck first = limited.submit(submission(PushPlatform.ANDROID, TrafficClass.NOTIFICATION, false));
        ProviderAck second = limited.submit(submission(PushPlatform.ANDROID, TrafficClass.NOTIFICATION, false));

        // Assert
        assertThat(first.isAccepted()).isTrue();
        assertThat(second.responseCode()).isEqualTo(FcmPushAdapter.THROTTLED_CODE);
        assertThat(second.isRetryable()).isTrue();
        google.verify(1, postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    private FcmPushAdapter adapter(RateLimit rateLimit) {
        return adapter(rateLimit, false);
    }

    private FcmPushAdapter adapter(RateLimit rateLimit, boolean iosDelivery) {
        FcmJson json = new FcmJson();
        return new FcmPushAdapter(
                properties(rateLimit, iosDelivery),
                new FcmMessageCodec(json),
                json,
                new ProviderSupport(
                        new ProviderCallExecutor(breakers, RetryRegistry.ofDefaults(), FixedClock.standard()),
                        throttle,
                        ProviderRuntimeSettings.configurationOnly(),
                        FixedClock.standard(),
                        new ProviderRestClients()));
    }

    private FcmProperties properties(RateLimit rateLimit, boolean iosDelivery) {
        return new FcmProperties(
                true,
                "FCM",
                new FcmProperties.Credentials(serviceAccount()),
                new FcmProperties.Sending(null, Duration.ofMinutes(10), iosDelivery, true),
                new FcmProperties.OAuth(google.baseUrl() + TOKEN_PATH, null),
                rateLimit,
                new ProviderHttpProperties(google.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2)),
                new ProviderResilienceProperties(2, Duration.ofMillis(10), null, null, null, null, null, null));
    }

    /** A service account key of the shape Google hands out, with a throwaway RSA key. */
    private String serviceAccount() {
        String privateKey;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            privateKey = "-----BEGIN PRIVATE KEY-----\\n"
                    + Base64.getEncoder()
                            .encodeToString(
                                    generator.generateKeyPair().getPrivate().getEncoded())
                    + "\\n-----END PRIVATE KEY-----\\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String document = """
                {"type":"service_account","project_id":"hamkor-mobile",
                 "client_email":"push@hamkor-mobile.iam.gserviceaccount.com",
                 "token_uri":"%s","private_key":"%s"}
                """.formatted(google.baseUrl() + TOKEN_PATH, privateKey);
        return document;
    }

    private static PushSubmission submission(PushPlatform platform, TrafficClass trafficClass, boolean test) {
        ProviderRef provider =
                new ProviderRef(ProviderId.newId(), ProviderCode.of("FCM"), Channel.PUSH, AdapterType.of("fcm-http"));
        return new PushSubmission(
                provider,
                MessageId.of(UuidV7.generate()),
                PushToken.of("device-a", platform),
                PushContent.of("Hamkorbank", "Hisobingiz to'ldirildi"),
                Timing.withTtl(Duration.ofMinutes(5)),
                null,
                new SubmissionContext(trafficClass, Priority.NORMAL, CorrelationId.of("corr-1"), test));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withBody(body.getBytes(StandardCharsets.UTF_8));
    }
}
