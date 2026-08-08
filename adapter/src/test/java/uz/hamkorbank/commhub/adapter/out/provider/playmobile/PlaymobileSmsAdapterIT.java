package uz.hamkorbank.commhub.adapter.out.provider.playmobile;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.adapter.out.provider.ProviderStubs;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileProperties.Credentials;
import uz.hamkorbank.commhub.adapter.out.provider.playmobile.PlaymobileProperties.Sending;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderThrottle;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
import uz.hamkorbank.commhub.application.service.support.ProviderMessageIdFactory;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.Timing;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.CorrelationId;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;

/**
 * Contract test of the Playmobile adapter against stubs built from §9.1 and §18.1 (QA-04, PR-04).
 *
 * <p>Exercises the whole adapter — request shape, authentication, error classification, breaker — over
 * a real HTTP connection, which is the part a codec unit test cannot reach.
 */
@Tag("integration")
class PlaymobileSmsAdapterIT {

    private static final String SEND_URL = "/broker-api/send";

    private WireMockServer provider;
    private PlaymobileSmsAdapter adapter;
    private CircuitBreakerRegistry breakers;

    @BeforeEach
    void setUp() {
        provider = ProviderStubs.startServer();
        breakers = CircuitBreakerRegistry.ofDefaults();
        PlaymobileProperties properties = new PlaymobileProperties(
                true,
                "PLAYMOBILE",
                new Credentials("playmobile/username", "playmobile/password"),
                new Sending("3700", "HB", null, null),
                RateLimit.unlimited(),
                new ProviderHttpProperties(
                        provider.baseUrl() + "/broker-api", Duration.ofSeconds(2), Duration.ofSeconds(2)),
                new ProviderResilienceProperties(
                        1, Duration.ofMillis(1), 1.0d, 0.0d, 50.0f, 20, 10, Duration.ofSeconds(30)));
        PlaymobileJson json = new PlaymobileJson();
        adapter = new PlaymobileSmsAdapter(
                properties,
                new PlaymobileSendCodec(json),
                new ProviderCallExecutor(breakers, RetryRegistry.ofDefaults(), FixedClock.standard()),
                new ProviderThrottle(),
                new ProviderMessageIdFactory(),
                ProviderStubs.secrets("playmobile/username", "hamkor", "playmobile/password", "s3cr3t"),
                FixedClock.standard(),
                new ProviderRestClients());
    }

    @AfterEach
    void tearDown() {
        provider.stop();
    }

    @Test
    @DisplayName("§9.1: an accepted send posts the documented document with Basic auth and returns the message-id")
    void acceptedSend() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SEND_URL)).willReturn(aResponse().withStatus(200)));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821"));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(ack.providerMessageIdOptional()).isPresent();
        assertThat(ack.providerMessageId().value()).startsWith("HB").hasSizeLessThanOrEqualTo(20);
        provider.verify(postRequestedFor(urlEqualTo(SEND_URL))
                .withHeader("Authorization", equalTo(basicAuth()))
                .withRequestBody(matchingJsonPath("$.messages[0].recipient", equalTo("998901234567")))
                .withRequestBody(matchingJsonPath("$.messages[0].sms.content.text", equalTo("Kod: 4821")))
                .withRequestBody(matchingJsonPath("$.priority", equalTo("realtime"))));
    }

    @Test
    @DisplayName("§18.1 code 406: a content refusal ends the message and never touches the breaker")
    void contentRefusalIsPermanent() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SEND_URL))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error-code\":\"406\",\"error-description\":\"Invalid content\"}")));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821"));

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(ack.errorDescription()).isEqualTo("Invalid content");
        assertThat(breakers.circuitBreaker("PLAYMOBILE").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("§18.1 code 102: an account lock opens the breaker and the next send is not attempted")
    void accountLockOpensTheBreaker() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SEND_URL))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error-code\":\"102\"}")));

        // Act
        ProviderAck locked = adapter.submit(submission("Kod: 4821"));
        ProviderAck afterwards = adapter.submit(submission("Kod: 1234"));

        // Assert
        assertThat(locked.isBlocking()).isTrue();
        assertThat(breakers.circuitBreaker("PLAYMOBILE").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(afterwards.responseCode()).isEqualTo(ProviderCallExecutor.CIRCUIT_OPEN_CODE);
        assertThat(afterwards.isRetryable()).isTrue();
        provider.verify(1, postRequestedFor(urlEqualTo(SEND_URL)));
    }

    @Test
    @DisplayName("PR-01: an HTTP 5xx is retryable, so the message keeps its chance on this or another provider")
    void serverErrorIsRetryable() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SEND_URL)).willReturn(aResponse().withStatus(503)));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821"));

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        assertThat(ack.responseCode()).isEqualTo("503");
    }

    @Test
    @DisplayName("PR-01: a provider that does not answer inside the read timeout produces a timed-out ack")
    void readTimeoutBecomesTimeoutAck() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SEND_URL))
                .willReturn(aResponse().withStatus(200).withFixedDelay(4_000)));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821"));

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.TIMEOUT);
        assertThat(ack.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("§9.1: a chunk is one request, and every message of it gets its own message-id back")
    void bulkSendIsOneRequest() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SEND_URL)).willReturn(aResponse().withStatus(200)));

        // Act
        List<ProviderAck> acks = adapter.submitBatch(List.of(submission("A"), submission("B")));

        // Assert
        assertThat(acks).hasSize(2);
        assertThat(acks).allSatisfy(ack -> assertThat(ack.isAccepted()).isTrue());
        assertThat(acks.get(0).providerMessageId()).isNotEqualTo(acks.get(1).providerMessageId());
        provider.verify(1, postRequestedFor(urlEqualTo(SEND_URL)).withRequestBody(matchingJsonPath("$.messages[1]")));
    }

    private static String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString("hamkor:s3cr3t".getBytes(StandardCharsets.UTF_8));
    }

    private static SmsSubmission submission(String text) {
        return new SmsSubmission(
                new ProviderRef(
                        ProviderId.newId(),
                        ProviderCode.of("PLAYMOBILE"),
                        Channel.SMS,
                        AdapterType.of("playmobile-http")),
                MessageId.newId(),
                null,
                Msisdn.of("998901234567"),
                SmsContent.of(text),
                Timing.immediate(),
                null,
                new SubmissionContext(TrafficClass.CRITICAL_OTP, Priority.REALTIME, CorrelationId.of("corr-1"), false));
    }
}
