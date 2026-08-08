package uz.hamkorbank.commhub.chaos;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import uz.hamkorbank.commhub.application.dto.ProviderHealthResult;
import uz.hamkorbank.commhub.application.dto.RouteEvaluationView;
import uz.hamkorbank.commhub.application.port.in.CheckProviderHealth;
import uz.hamkorbank.commhub.application.port.in.DispatchMessage;
import uz.hamkorbank.commhub.application.port.in.EvaluateRoute;
import uz.hamkorbank.commhub.application.port.in.command.CheckProviderHealthCommand;
import uz.hamkorbank.commhub.application.port.in.command.DispatchMessageCommand;
import uz.hamkorbank.commhub.application.port.in.query.RouteEvaluationQuery;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.support.HubConfiguration;
import uz.hamkorbank.commhub.support.HubTestContainers;
import uz.hamkorbank.commhub.support.ProviderStub;

/**
 * A provider that stops answering, and what the Hub does about it (QA-06, FR-6.3, PR-02).
 *
 * <p>Two mechanisms answer that, and they work on different timescales, which is exactly why both have
 * to be shown. The <b>saga</b> fails over inside the life of one message: the attempt budget on the
 * failing provider runs out and the next attempt goes to the reserve of the channel. The <b>health
 * monitor</b> works on the traffic as a whole: enough failed attempts in its window and the provider
 * stops being selectable at all, so the messages behind this one never touch it. The requirement of
 * ≤ 60 s failover is the second one — the monitor runs every 30 s over a 5 min window (FR-6.3) — and
 * here it is driven by hand, because a test that waited for the scheduler would be a test of
 * {@code @Scheduled}.
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "commhub.outbox.relay.poll-interval-ms=3600000",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h",
            "commhub.config.cache.refresh-interval=1s",
            // Один провайдер — одна попытка: failover должен произойти на следующей же попытке,
            // а не после того, как бюджет двух попыток истратится на молчащий провайдер.
            "commhub.sending.max-attempts-per-provider=1",
            "commhub.sending.max-total-attempts=4",
            // Пауза перед повтором сведена к нулю: тест проверяет выбор провайдера, а не будильник.
            "commhub.sending.initial-backoff=1ms",
            "commhub.sending.max-backoff=1ms",
            // Порог здоровья: две неудачные попытки в окне уже показательны для теста, в проме — 20.
            "commhub.provider.health.minimum-attempts=2",
            "commhub.provider.playmobile.enabled=true",
            "commhub.provider.playmobile.sending.originator=3700",
            "commhub.provider.playmobile.resilience.max-attempts=1",
            "commhub.provider.smsgate.enabled=true",
            "commhub.provider.smsgate.sending.sender=HAMKORBANK",
            "commhub.secrets.values.playmobile/username=hamkor",
            "commhub.secrets.values.playmobile/password=s3cr3t",
            "commhub.secrets.values.smsgate/login=hamkor",
            "commhub.secrets.values.smsgate/key=s3cr3t",
            "commhub.kafka.outbound.create-topics=true",
            "commhub.rest.rate-limit.enabled=false"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ProviderFailoverIT {

    private static final String PLAYMOBILE_SEND = "/broker-api/send";
    private static final String SMSGATE_SEND = "/api/v2/send";

    /** §18.2: код 0 — сообщение принято, {@code id} и {@code parts} — идентификатор и сегменты. */
    private static final String SMSGATE_ACCEPTED =
            "{\"status\":{\"code\":0,\"description\":\"success\"},\"id\":98765,\"parts\":1}";

    private final JdbcClient jdbc;
    private final ProviderConfigRepository providers;
    private final StreamRepository streams;
    private final DispatchMessage dispatcher;
    private final CheckProviderHealth health;
    private final EvaluateRoute routes;
    private final RestClient rest;

    ProviderFailoverIT(
            JdbcClient jdbc,
            ProviderConfigRepository providers,
            StreamRepository streams,
            DispatchMessage dispatcher,
            CheckProviderHealth health,
            EvaluateRoute routes,
            @Value("${local.server.port}") int port) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.streams = streams;
        this.dispatcher = dispatcher;
        this.health = health;
        this.routes = routes;
        this.rest = RestClient.create("http://localhost:" + port);
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        HubTestContainers.register(registry);
        registry.add("commhub.provider.playmobile.http.base-url", () -> ProviderStub.baseUrl() + "/broker-api");
        registry.add("commhub.provider.smsgate.http.base-url", ProviderStub::baseUrl);
    }

    @BeforeEach
    void configureTheContour() {
        ProviderStub.reset();
        if (jdbc.sql("SELECT count(*) FROM stream").query(Integer.class).single() == 0) {
            HubConfiguration.seed(providers, streams);
        }
        jdbc.sql("UPDATE provider SET health_status = 'UNKNOWN'").update();
        // Здоровье считается по окну попыток, а PostgreSQL один на весь прогон: удачные отправки
        // приёмочных сценариев попадают в то же окно и разбавляют долю ошибок до DEGRADED.
        jdbc.sql("DELETE FROM delivery_attempt").update();
        // Снапшот маршрутизации переживает правку в БД в пределах окна обновления (AD-07, NF-07),
        // поэтому тест ждёт не время, а факт: основной провайдер снова выбирается.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(route().provider()).isEqualTo(HubConfiguration.PRIMARY));
    }

    /** Маршрут, который получило бы следующее OTP-сообщение этого потока (FR-2.8). */
    private RouteEvaluationView route() {
        return routes.evaluate(new RouteEvaluationQuery(
                HubConfiguration.OTP_STREAM,
                Recipient.ofMsisdn(Msisdn.of("998901234567")),
                Channel.SMS,
                TrafficClass.CRITICAL_OTP,
                null,
                "Kod: 483920"));
    }

    @Test
    @DisplayName("QA-06: the primary provider stops answering and the message leaves through the reserve")
    void failsOverToTheReserveWithinTheAttemptBudget() {
        // Arrange — Playmobile отвечает 503 (ответа по существу нет), SMS Gate принимает
        ProviderStub.server()
                .stubFor(
                        post(urlEqualTo(PLAYMOBILE_SEND)).willReturn(aResponse().withStatus(503)));
        ProviderStub.server()
                .stubFor(post(urlEqualTo(SMSGATE_SEND))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(SMSGATE_ACCEPTED)));
        MessageId messageId = submitOtp();

        // Act — заход за заходом, как это делал бы фоновый диспетчер: один вызов — одна попытка
        dispatchUntilSent(messageId);

        // Assert — вторая попытка ушла на резервного провайдера, сообщение живо
        assertThat(attemptProviders(messageId)).containsExactly("PLAYMOBILE", "SMSGATE");
        assertThat(statusOf(messageId)).isEqualTo("SENT_TO_PROVIDER");
        ProviderStub.server().verify(1, postRequestedFor(urlEqualTo(SMSGATE_SEND)));
    }

    @Test
    @DisplayName("FR-6.3: failed attempts take the provider DOWN, and routing stops choosing it")
    void healthMonitorTakesTheProviderOutOfRouting() {
        // Arrange — сообщения, каждое из которых упирается в молчащий Playmobile
        ProviderStub.server()
                .stubFor(
                        post(urlEqualTo(PLAYMOBILE_SEND)).willReturn(aResponse().withStatus(503)));
        ProviderStub.server()
                .stubFor(post(urlEqualTo(SMSGATE_SEND))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"status\":{\"code\":0},\"id\":1,\"parts\":1}")));
        for (int i = 0; i < 3; i++) {
            dispatcher.dispatch(DispatchMessageCommand.of(submitOtp()));
        }

        // Act — то, что делает планировщик раз в 30 с (PR-02)
        ProviderHealthResult result = health.check(CheckProviderHealthCommand.allChannels());

        // Assert — провайдер снят с выбора, и маршрут для следующего сообщения ведёт к резерву
        assertThat(result.checked()).isPositive();
        assertThat(healthOf(HubConfiguration.PRIMARY.value())).isEqualTo("DOWN");
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(route().provider()).isEqualTo(HubConfiguration.RESERVE));
        assertThat(route().routed()).isTrue();
    }

    /**
     * Крутит сагу, пока сообщение не уйдёт провайдеру или не кончится бюджет попыток.
     *
     * <p>Один вызов {@code dispatch} — одна попытка: сага не крутит цикл внутри себя, иначе первый же
     * недоступный провайдер занял бы поток на всё время бюджета. Failover виден именно между вызовами.
     */
    private void dispatchUntilSent(MessageId messageId) {
        for (int attempt = 0; attempt < 4 && !"SENT_TO_PROVIDER".equals(statusOf(messageId)); attempt++) {
            dispatcher.dispatch(DispatchMessageCommand.of(messageId));
        }
    }

    private MessageId submitOtp() {
        String body = """
                {
                  "schemaVersion": "1.0",
                  "streamId": "%s",
                  "externalMessageId": "chaos-%s",
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "Kod: 483920" } }
                }
                """.formatted(HubConfiguration.OTP_STREAM.value(), UUID.randomUUID());
        Map<?, ?> accepted = rest.post()
                .uri("/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return MessageId.of(UUID.fromString((String) accepted.get("messageId")));
    }

    private List<String> attemptProviders(MessageId messageId) {
        return jdbc.sql("SELECT provider_code FROM delivery_attempt WHERE message_id = :id ORDER BY attempt_no")
                .param("id", messageId.value())
                .query(String.class)
                .list();
    }

    private String statusOf(MessageId messageId) {
        return jdbc.sql("SELECT status FROM message WHERE id = :id")
                .param("id", messageId.value())
                .query(String.class)
                .single();
    }

    private String healthOf(String providerCode) {
        return jdbc.sql("SELECT health_status FROM provider WHERE code = :code")
                .param("code", providerCode)
                .query(String.class)
                .single();
    }
}
