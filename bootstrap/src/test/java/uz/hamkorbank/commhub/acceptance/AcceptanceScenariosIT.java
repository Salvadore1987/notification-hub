package uz.hamkorbank.commhub.acceptance;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.application.dto.OutboxRelayResult;
import uz.hamkorbank.commhub.application.port.in.PublishOutboxEvents;
import uz.hamkorbank.commhub.application.port.in.command.PublishOutboxEventsCommand;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.support.HubConfiguration;
import uz.hamkorbank.commhub.support.HubTestContainers;
import uz.hamkorbank.commhub.support.ProviderStub;

/**
 * The three pilot flows of QA-08 on a fully assembled Hub: OTP, transactional, bulk.
 *
 * <p>Everything is real except the provider: PostgreSQL, the broker, the whole Spring context, the HTTP
 * ingress of §8.2 and the Kafka ingress of §8.1. Playmobile is a WireMock stub built from §9.1 — the
 * Bank runs this against the provider's own test contour, and a test that dialled a real SMS gateway
 * would be a test that sends SMS.
 *
 * <p>Nothing here is driven by hand any more. Until the dispatcher existed, these scenarios called
 * {@code DispatchMessage} themselves and this comment explained why — the running application picked up
 * no {@code QUEUED} message and sent none. Now the schedulers of {@code adapter/in/scheduler} do it, and
 * the tests wait for the outcome instead of producing it (ADR-0039).
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            // Планировщики отодвинуты: сценарии дёргают use case'ы явно, чтобы шаг было видно в тесте.
            "commhub.outbox.relay.poll-interval-ms=3600000",
            // Диспетчер включён намеренно: приёмка проверяет продукт, а не use case поодиночке.
            "commhub.dispatch.critical-otp.poll-interval=200ms",
            "commhub.dispatch.transactional.poll-interval=200ms",
            "commhub.dispatch.notification.poll-interval=200ms",
            "commhub.dispatch.expiry.enabled=false",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h",
            "commhub.config.cache.refresh-interval=1s",
            "commhub.provider.playmobile.enabled=true",
            "commhub.provider.playmobile.sending.originator=3700",
            // Креды: в контуре ссылка указывает на переменную окружения (ADR-0036), в тесте — на
            // литерал, потому что переменную окружения JVM изнутри теста не задать.
            "commhub.provider.playmobile.credentials.username-ref=playmobile/username",
            "commhub.provider.playmobile.credentials.password-ref=playmobile/password",
            "commhub.secrets.values.playmobile/username=hamkor",
            "commhub.secrets.values.playmobile/password=s3cr3t",
            "commhub.kafka.outbound.create-topics=true",
            "commhub.kafka.outbound.status-topic=" + AcceptanceScenariosIT.STATUS_TOPIC,
            "commhub.kafka.inbound.topics.transactional=" + AcceptanceScenariosIT.TRANSACTIONAL_TOPIC,
            "commhub.rest.rate-limit.enabled=false"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AcceptanceScenariosIT {

    static final String STATUS_TOPIC = "acceptance.outbound.status.v1";
    static final String TRANSACTIONAL_TOPIC = "acceptance.inbound.transactional.v1";

    private static final String SEND_URL = "/broker-api/send";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final JdbcClient jdbc;
    private final ProviderConfigRepository providers;
    private final StreamRepository streams;
    private final PublishOutboxEvents relay;
    private final RestClient rest;

    AcceptanceScenariosIT(
            JdbcClient jdbc,
            ProviderConfigRepository providers,
            StreamRepository streams,
            PublishOutboxEvents relay,
            @Value("${local.server.port}") int port) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.streams = streams;
        this.relay = relay;
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
    }

    @Test
    @DisplayName("QA-08 OTP: REST submission → Playmobile → delivery report → DELIVERED on the status topic")
    void otpFlow() {
        // Arrange — Playmobile accepts the send (§9.1: HTTP 200 with no body)
        ProviderStub.server()
                .stubFor(post(urlEqualTo(SEND_URL)).willReturn(aResponse().withStatus(200)));
        String externalId = "otp-" + UUID.randomUUID();

        // Act — приём
        ResponseEntity<Map> accepted = submit(otpBody(externalId));
        MessageId messageId =
                MessageId.of(UUID.fromString((String) accepted.getBody().get("messageId")));

        // Assert — принято в конвейер и поставлено в очередь на отправку (§5.1)
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Act — отправку делает диспетчер сам (AD-04, ADR-0039), тест её только дожидается
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(statusOf(messageId))
                .isEqualTo("SENT_TO_PROVIDER"));
        String providerMessageId = providerMessageIdOf(messageId);
        ResponseEntity<String> callback = deliveryReport(providerMessageId, "delivered");

        // Assert — попытка записана на нужного провайдера, статус DELIVERED (ST-01)
        ProviderStub.server()
                .verify(postRequestedFor(urlEqualTo(SEND_URL))
                        .withRequestBody(matchingJsonPath("$.messages[0].recipient", WireMock.equalTo("998901234567")))
                        .withRequestBody(matchingJsonPath("$.priority", WireMock.equalTo("realtime"))));
        assertThat(providerOf(messageId)).isEqualTo(HubConfiguration.PRIMARY.value());
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(messageId)).isEqualTo("DELIVERED");

        // Act — публикация статусов в топик систем-источников (AD-03, §6.4)
        OutboxRelayResult published = relay.publish(PublishOutboxEventsCommand.defaults());

        // Assert — каждое событие сообщения уехало, и ни одно не осталось неопубликованным
        assertThat(published.published()).isPositive();
        assertThat(unpublishedEvents()).isZero();
        assertThat(statusEventsOf(messageId)).contains("DELIVERED");
    }

    @Test
    @DisplayName("QA-08 transactional: a document on the Kafka topic reaches the same pipeline (IK-01)")
    void transactionalFlow() throws Exception {
        // Arrange
        ProviderStub.server()
                .stubFor(post(urlEqualTo(SEND_URL)).willReturn(aResponse().withStatus(200)));
        String externalId = "trx-" + UUID.randomUUID();

        // Act — источник кладёт IK-03 в свой топик, не зная ничего про HTTP
        publish(TRANSACTIONAL_TOPIC, transactionalBody(externalId));

        // Assert — сообщение принято тем же конвейером и с классом трафика своего топика (TC-01)
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(idOf(externalId))
                .isNotNull());
        MessageId messageId = idOf(externalId);
        assertThat(trafficClassOf(messageId)).isEqualTo("TRANSACTIONAL");

        // Act + Assert — диспетчер класса TRANSACTIONAL забирает своё сам
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(statusOf(messageId))
                .isEqualTo("SENT_TO_PROVIDER"));
    }

    @Test
    @DisplayName("QA-08 bulk: a batch is created, filled by chunks, started and progresses (FR-1.6, FR-3.1)")
    void bulkFlow() {
        // Arrange
        ProviderStub.server()
                .stubFor(post(urlEqualTo(SEND_URL)).willReturn(aResponse().withStatus(200)));
        String batchId = createBatch(5);

        // Act — чанк элементов; загрузка первого чанка сама переводит батч в работу
        ResponseEntity<Map> items = addItems(batchId, 5);

        // Assert — принято ровно столько, сколько отправлено
        assertThat(items.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(items.getBody().get("accepted")).isEqualTo(5);
        assertThat(batch(batchId).getBody().get("status")).isEqualTo("PROCESSING");

        // Act + Assert — управление рассылкой оператором (FR-3.2): пауза и возобновление
        assertThat(act(batchId, "pause").getBody().get("status")).isEqualTo("PAUSED");
        assertThat(act(batchId, "resume").getBody().get("status")).isEqualTo("PROCESSING");

        // Act + Assert — рассылку вычёрпывает диспетчер класса NOTIFICATION
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            assertThat(statusesOfBatch(batchId)).containsOnly("SENT_TO_PROVIDER");
            assertThat(attemptsOfBatch(batchId)).isEqualTo(5);
        });
        ProviderStub.server().verify(5, postRequestedFor(urlEqualTo(SEND_URL)));

        // Assert — счётчики прогресса рассылки (FR-3.1). Раньше здесь стоял комментарий о том,
        // почему ассерта нет: их никто не увеличивал (долг Д-2). Теперь это ассерт (ADR-0040).
        //
        // processed остаётся нулём намеренно: SMS на SENT_TO_PROVIDER ещё в полёте — провайдер о
        // доставке не отчитался, и терминальной она не является (в отличие от push, PU-12).
        // total равен пяти, а не десяти: заголовок объявил пять и чанк привёз те же пять —
        // складывать их значило бы, что processed никогда не догонит total и рассылка не закроется
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> assertThat(progressOf(batchId))
                .containsEntry("total", 5)
                .containsEntry("sent", 5)
                .containsEntry("processed", 0)
                .containsEntry("failed", 0));
    }

    // ------------------------------------------------------------------ HTTP

    private ResponseEntity<Map> submit(String body) {
        return rest.post()
                .uri("/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
    }

    private ResponseEntity<String> deliveryReport(String providerMessageId, String status) {
        String body = """
                {"message-id":"%s","channel":"sms","status":"%s","description":"ok"}
                """.formatted(providerMessageId, status);
        return rest.post()
                .uri("/api/callbacks/PLAYMOBILE")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private String createBatch(int total) {
        String body = """
                {"streamId":"%s","channel":"SMS","expectedTotal":%d}
                """.formatted(HubConfiguration.BULK_STREAM.value(), total);
        ResponseEntity<Map> created = rest.post()
                .uri("/api/v1/batches")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        return (String) created.getBody().get("batchId");
    }

    private ResponseEntity<Map> addItems(String batchId, int count) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < count; i++) {
            items.append(i == 0 ? "" : ",").append("""
                            {"externalMessageId":"bulk-%s-%d","recipient":{"msisdn":"99890123%04d"},
                             "content":{"sms":{"text":"Hamkorbank: novosti mesyaca"}}}
                            """.formatted(batchId, i, 1000 + i));
        }
        return rest.post()
                .uri("/api/v1/batches/{id}/items?streamId={stream}", batchId, HubConfiguration.BULK_STREAM.value())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"items\":[" + items + "]}")
                .retrieve()
                .toEntity(Map.class);
    }

    private ResponseEntity<Map> act(String batchId, String action) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Commhub-Actor", "acceptance");
        return rest.post()
                .uri("/api/v1/batches/{id}/actions/{action}", batchId, action)
                .headers(existing -> existing.addAll(headers))
                .body(HttpEntity.EMPTY)
                .retrieve()
                .toEntity(Map.class);
    }

    private ResponseEntity<Map> batch(String batchId) {
        return rest.get().uri("/api/v1/batches/{id}", batchId).retrieve().toEntity(Map.class);
    }

    /** Счётчики карточки рассылки, как их отдаёт §8.2 (FR-3.1). */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> progressOf(String batchId) {
        return (Map<String, Integer>) batch(batchId).getBody().get("progress");
    }

    // ----------------------------------------------------------------- Kafka

    private static void publish(String topic, String value) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                HubTestContainers.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG,
                "all");
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, "998901234567", value)).get(30, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------- SQL

    private String statusOf(MessageId messageId) {
        return jdbc.sql("SELECT status FROM message WHERE id = :id")
                .param("id", messageId.value())
                .query(String.class)
                .single();
    }

    private String trafficClassOf(MessageId messageId) {
        return jdbc.sql("SELECT traffic_class FROM message WHERE id = :id")
                .param("id", messageId.value())
                .query(String.class)
                .single();
    }

    private MessageId idOf(String externalMessageId) {
        return jdbc.sql("SELECT id FROM message WHERE external_id = :external")
                .param("external", externalMessageId)
                .query(UUID.class)
                .optional()
                .map(MessageId::of)
                .orElse(null);
    }

    private String providerOf(MessageId messageId) {
        return jdbc.sql("SELECT provider_code FROM delivery_attempt WHERE message_id = :id ORDER BY attempt_no DESC")
                .param("id", messageId.value())
                .query(String.class)
                .list()
                .getFirst();
    }

    private String providerMessageIdOf(MessageId messageId) {
        return jdbc.sql("""
                        SELECT provider_message_id FROM delivery_attempt
                        WHERE message_id = :id ORDER BY attempt_no DESC
                        """)
                .param("id", messageId.value())
                .query(String.class)
                .list()
                .getFirst();
    }

    private List<String> statusEventsOf(MessageId messageId) {
        return jdbc.sql("SELECT payload -> 'outcome' ->> 'status' FROM outbox_event WHERE aggregate_id = :id")
                .param("id", messageId.value().toString())
                .query(String.class)
                .list();
    }

    private List<String> statusesOfBatch(String batchId) {
        return jdbc.sql("SELECT status FROM message WHERE batch_id = :batch")
                .param("batch", UUID.fromString(batchId))
                .query(String.class)
                .list();
    }

    private int attemptsOfBatch(String batchId) {
        return jdbc.sql("""
                        SELECT count(*) FROM delivery_attempt a
                        JOIN message m ON m.id = a.message_id
                        WHERE m.batch_id = :batch
                        """)
                .param("batch", UUID.fromString(batchId))
                .query(Integer.class)
                .single();
    }

    private int unpublishedEvents() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Integer.class)
                .single();
    }

    private static String otpBody(String externalId) {
        return """
                {
                  "schemaVersion": "1.0",
                  "streamId": "%s",
                  "externalMessageId": "%s",
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "Kod: 483920. Nikomu ne soobshchayte." } },
                  "correlationId": "acceptance-otp"
                }
                """.formatted(HubConfiguration.OTP_STREAM.value(), externalId);
    }

    private static String transactionalBody(String externalId) {
        return """
                {
                  "schemaVersion": "1.0",
                  "streamId": "%s",
                  "externalMessageId": "%s",
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "Spisanie 250 000 UZS. Ostatok 1 200 000 UZS." } }
                }
                """.formatted(HubConfiguration.TRANSACTIONAL_STREAM.value(), externalId);
    }
}
