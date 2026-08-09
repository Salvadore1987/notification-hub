package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

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
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import uz.hamkorbank.commhub.adapter.out.provider.FixedClock;
import uz.hamkorbank.commhub.adapter.out.provider.ProviderStubs;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateProperties.Credentials;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateProperties.Sending;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderResilienceProperties;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRuntimeSettings;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderSupport;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderThrottle;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.SubmissionContext;
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
 * Contract test of the SMS Gate adapter against stubs built from §9.2 and §18.2 (QA-04, PR-04).
 *
 * <p>The cases that matter here are the ones where SMS Gate differs from the primary provider: the
 * verdict travels in the body of a 200, the provider assigns the identifier, and a chunk is answered
 * element by element.
 */
@Tag("integration")
class SmsGateSmsAdapterIT {

    private WireMockServer provider;
    private SmsGateSmsAdapter adapter;
    private ProviderThrottle throttle;
    private CircuitBreakerRegistry breakers;

    @BeforeEach
    void setUp() {
        provider = ProviderStubs.startServer();
        breakers = CircuitBreakerRegistry.ofDefaults();
        throttle = new ProviderThrottle();
        adapter = new SmsGateSmsAdapter(
                properties(RateLimit.unlimited()),
                new SmsGateSendCodec(new SmsGateJson()),
                new ProviderSupport(
                        new ProviderCallExecutor(breakers, RetryRegistry.ofDefaults(), FixedClock.standard()),
                        throttle,
                        ProviderRuntimeSettings.configurationOnly(),
                        ProviderStubs.secrets("smsgate/login", "hamkor", "smsgate/key", "k3y"),
                        FixedClock.standard(),
                        new ProviderRestClients()));
    }

    @AfterEach
    void tearDown() {
        provider.stop();
    }

    @Test
    @DisplayName("§9.2: an accepted send carries login/key/sender/phone/text and returns the provider id")
    void acceptedSend() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SmsGateProperties.SEND_PATH))
                .willReturn(json("{\"status\":{\"code\":0,\"description\":\"success\"},\"id\":98765,\"parts\":1}")));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821", TrafficClass.CRITICAL_OTP));

        // Assert
        assertThat(ack.isAccepted()).isTrue();
        assertThat(ack.providerMessageId().value()).isEqualTo("98765");
        provider.verify(postRequestedFor(urlEqualTo(SmsGateProperties.SEND_PATH))
                .withRequestBody(matchingJsonPath("$.login", equalTo("hamkor")))
                .withRequestBody(matchingJsonPath("$.key", equalTo("k3y")))
                .withRequestBody(matchingJsonPath("$.phone", equalTo("998901234567")))
                .withRequestBody(matchingJsonPath("$.weight", equalTo("10"))));
    }

    @Test
    @DisplayName("§18.2 code 1: the spam limit sends the message elsewhere without blaming the provider")
    void spamLimitIsRetryableWithoutOpeningTheBreaker() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SmsGateProperties.SEND_PATH))
                .willReturn(json("{\"status\":{\"code\":1,\"description\":\"spam\"}}")));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821", TrafficClass.CRITICAL_OTP));

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        assertThat(ack.responseCode()).isEqualTo("1");
        assertThat(breakers.circuitBreaker("SMSGATE").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("§18.2 code 13: a wrong key opens the breaker, because every message would fail identically")
    void wrongKeyOpensTheBreaker() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SmsGateProperties.SEND_PATH)).willReturn(json("{\"status\":{\"code\":13}}")));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821", TrafficClass.TRANSACTIONAL));

        // Assert
        assertThat(ack.isBlocking()).isTrue();
        assertThat(breakers.circuitBreaker("SMSGATE").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("§18.2 code 20: a blacklisted number is refused and marked as no longer deliverable")
    void blacklistedNumberInvalidatesTheRecipient() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SmsGateProperties.SEND_PATH)).willReturn(json("{\"status\":{\"code\":20}}")));

        // Act
        ProviderAck ack = adapter.submit(submission("Text", TrafficClass.NOTIFICATION));

        // Assert
        assertThat(ack.result()).isEqualTo(AttemptResult.REJECTED);
        assertThat(ack.invalidRecipient()).isTrue();
    }

    @Test
    @DisplayName("SG-02: a chunk is answered element by element — one accepted, one refused")
    void batchIsAnsweredPerElement() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SmsGateProperties.SEND_BATCH_PATH)).willReturn(json("""
                        {"status":{"code":0},"messages":[
                          {"seq":1,"id":1001,"code":0,"parts":1},
                          {"seq":2,"status":"blacklist"}
                        ]}
                        """)));

        // Act
        List<ProviderAck> acks = adapter.submitBatch(
                List.of(submission("A", TrafficClass.NOTIFICATION), submission("B", TrafficClass.NOTIFICATION)));

        // Assert
        assertThat(acks).hasSize(2);
        assertThat(acks.get(0).isAccepted()).isTrue();
        assertThat(acks.get(0).providerMessageId().value()).isEqualTo("1001");
        assertThat(acks.get(1).result()).isEqualTo(AttemptResult.ERROR);
        assertThat(acks.get(1).invalidRecipient()).isTrue();
    }

    @Test
    @DisplayName("§9.2: /api/v2/send is not retried inside the attempt — one call, one SMS")
    void doesNotRetryInsideTheAttempt() {
        // Arrange
        provider.stubFor(post(urlEqualTo(SmsGateProperties.SEND_PATH))
                .willReturn(aResponse().withStatus(503)));

        // Act
        ProviderAck ack = adapter.submit(submission("Kod: 4821", TrafficClass.CRITICAL_OTP));

        // Assert
        assertThat(ack.isRetryable()).isTrue();
        provider.verify(1, postRequestedFor(urlEqualTo(SmsGateProperties.SEND_PATH)));
    }

    @Test
    @DisplayName("FR-2.5/§18.2: once the hourly ceiling of a number is reached, the provider is not called")
    void throttledMessageNeverReachesTheProvider() {
        // Arrange
        adapter = new SmsGateSmsAdapter(
                properties(new RateLimit(0, 0, 1)),
                new SmsGateSendCodec(new SmsGateJson()),
                new ProviderSupport(
                        new ProviderCallExecutor(breakers, RetryRegistry.ofDefaults(), FixedClock.standard()),
                        throttle,
                        ProviderRuntimeSettings.configurationOnly(),
                        ProviderStubs.secrets("smsgate/login", "hamkor", "smsgate/key", "k3y"),
                        FixedClock.standard(),
                        new ProviderRestClients()));
        provider.stubFor(
                post(urlEqualTo(SmsGateProperties.SEND_PATH)).willReturn(json("{\"status\":{\"code\":0},\"id\":1}")));

        // Act
        ProviderAck first = adapter.submit(submission("A", TrafficClass.NOTIFICATION));
        ProviderAck second = adapter.submit(submission("B", TrafficClass.NOTIFICATION));

        // Assert
        assertThat(first.isAccepted()).isTrue();
        assertThat(second.responseCode()).isEqualTo(SmsGateSmsAdapter.THROTTLED_CODE);
        assertThat(second.isRetryable()).isTrue();
        provider.verify(1, postRequestedFor(urlEqualTo(SmsGateProperties.SEND_PATH)));
    }

    private SmsGateProperties properties(RateLimit rateLimit) {
        return new SmsGateProperties(
                true,
                "SMSGATE",
                new Credentials("smsgate/login", "smsgate/key"),
                new Sending("3700", null),
                SmsGateProperties.Reconciliation.defaults(),
                rateLimit,
                new ProviderHttpProperties(provider.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2)),
                new ProviderResilienceProperties(
                        1, Duration.ofMillis(1), 1.0d, 0.0d, 50.0f, 20, 10, Duration.ofSeconds(30)));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static SmsSubmission submission(String text, TrafficClass trafficClass) {
        return new SmsSubmission(
                new ProviderRef(
                        ProviderId.newId(), ProviderCode.of("SMSGATE"), Channel.SMS, AdapterType.of("smsgate-http")),
                MessageId.newId(),
                null,
                Msisdn.of("998901234567"),
                SmsContent.of(text),
                Timing.immediate(),
                null,
                new SubmissionContext(trafficClass, Priority.NORMAL, CorrelationId.of("corr-1"), false));
    }
}
