package uz.hamkorbank.commhub.dispatch;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.application.port.in.DispatchMessage;
import uz.hamkorbank.commhub.application.port.in.command.DispatchMessageCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.DispatchClaim;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.support.HubConfiguration;
import uz.hamkorbank.commhub.support.HubTestContainers;
import uz.hamkorbank.commhub.support.ProviderStub;

/**
 * Two dispatchers over one queue must never hand the same message to a provider (AD-04, ADR-0039).
 *
 * <p>This is the test the whole claim mechanism exists for. Before it, {@code findDispatchable} was a
 * plain {@code SELECT … ORDER BY accepted_at LIMIT} in its own read-only transaction: two pods asking
 * the same question got the same rows, and both called the provider. For SMS Gate, whose
 * {@code /api/v2/send} takes no client-side identifier, that is a second SMS to the customer.
 *
 * <p>The two loops here are what two pods are: different {@code owner}s, each claiming in its own
 * transaction and then running the real saga. Isolation comes from the row lock and the lease, not from
 * being in different JVMs — so one JVM is enough to show it, and a failure here is a real duplicate.
 *
 * <p>The scheduler itself is switched off: the test drives claim-and-dispatch directly so that the
 * assertion is about the claim, not about how often {@code @Scheduled} happened to fire.
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "commhub.outbox.relay.poll-interval-ms=3600000",
            "commhub.dispatch.enabled=false",
            "commhub.dispatch.expiry.enabled=false",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h",
            "commhub.provider.playmobile.enabled=true",
            "commhub.provider.playmobile.sending.originator=3700",
            "commhub.provider.playmobile.credentials.username-ref=playmobile/username",
            "commhub.provider.playmobile.credentials.password-ref=playmobile/password",
            "commhub.secrets.values.playmobile/username=hamkor",
            "commhub.secrets.values.playmobile/password=s3cr3t",
            "commhub.kafka.outbound.create-topics=true",
            "commhub.rest.rate-limit.enabled=false"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DispatcherConcurrencyIT {

    private static final String PLAYMOBILE_SEND = "/broker-api/send";

    /** How many messages the two loops race over; enough for a lost claim to show up reliably. */
    private static final int MESSAGES = 60;

    private static final int LOOPS = 2;

    private final JdbcClient jdbc;
    private final MessageRepository messages;
    private final DispatchMessage dispatcher;
    private final ClockPort clock;
    private final ProviderConfigRepository providers;
    private final StreamRepository streams;
    private final RestClient rest;

    DispatcherConcurrencyIT(
            JdbcClient jdbc,
            MessageRepository messages,
            DispatchMessage dispatcher,
            ClockPort clock,
            ProviderConfigRepository providers,
            StreamRepository streams,
            @Value("${local.server.port}") int port) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.providers = providers;
        this.streams = streams;
        this.rest = RestClient.create("http://localhost:" + port);
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        HubTestContainers.register(registry);
        registry.add("commhub.provider.playmobile.http.base-url", () -> ProviderStub.baseUrl() + "/broker-api");
    }

    @BeforeEach
    void configureTheContour() {
        ProviderStub.reset();
        if (jdbc.sql("SELECT count(*) FROM stream").query(Integer.class).single() == 0) {
            HubConfiguration.seed(providers, streams);
        }
        jdbc.sql("DELETE FROM delivery_attempt").update();
        jdbc.sql("DELETE FROM message_status_history").update();
        jdbc.sql("DELETE FROM message").update();
    }

    @Test
    @DisplayName("AD-04: two dispatchers share the queue instead of duplicating it")
    void twoDispatchersNeverSendTheSameMessageTwice() throws Exception {
        // Arrange — провайдер принимает всё, поэтому единственная причина второго вызова — дубль
        ProviderStub.server()
                .stubFor(post(urlEqualTo(PLAYMOBILE_SEND))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"messages\":[{\"status\":\"accepted\"}]}")));
        for (int i = 0; i < MESSAGES; i++) {
            submitOtp();
        }

        // Act — два пода: разные owner'ы, одна очередь, никакой координации между ними
        try (ExecutorService pods = Executors.newFixedThreadPool(LOOPS)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int pod = 0; pod < LOOPS; pod++) {
                String owner = "pod-" + pod;
                runs.add(pods.submit(() -> drain(owner)));
            }
            int dispatched = 0;
            for (Future<Integer> run : runs) {
                dispatched += run.get(2, TimeUnit.MINUTES);
            }
            assertThat(dispatched).isEqualTo(MESSAGES);
        }

        // Assert — ровно одна отправка на сообщение, и ни одного сообщения с двумя попытками
        ProviderStub.server().verify(MESSAGES, postRequestedFor(urlEqualTo(PLAYMOBILE_SEND)));
        assertThat(count("SELECT count(*) FROM delivery_attempt")).isEqualTo(MESSAGES);
        assertThat(count("SELECT count(*) FROM (SELECT message_id FROM delivery_attempt"
                        + " GROUP BY message_id HAVING count(*) > 1) AS duplicated"))
                .isZero();
        assertThat(count("SELECT count(*) FROM message WHERE status <> 'SENT_TO_PROVIDER'"))
                .isZero();
    }

    /** One pod: claim what it can and send it, until the queue is empty. */
    private int drain(String owner) {
        int dispatched = 0;
        for (int pass = 0; pass < MESSAGES; pass++) {
            List<MessageId> claimed = messages.claimDispatchable(
                    TrafficClass.CRITICAL_OTP, new DispatchClaim(owner, clock.now(), Duration.ofMinutes(2), 10));
            if (claimed.isEmpty()) {
                return dispatched;
            }
            for (MessageId messageId : claimed) {
                dispatcher.dispatch(DispatchMessageCommand.of(messageId));
                dispatched++;
            }
        }
        return dispatched;
    }

    private void submitOtp() {
        String body = """
                {
                  "schemaVersion": "1.0",
                  "streamId": "%s",
                  "externalMessageId": "race-%s",
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "Kod: 483920" } }
                }
                """.formatted(HubConfiguration.OTP_STREAM.value(), UUID.randomUUID());
        rest.post()
                .uri("/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
