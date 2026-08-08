package uz.hamkorbank.commhub.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import uz.hamkorbank.commhub.application.dto.OutboxRelayResult;
import uz.hamkorbank.commhub.application.port.in.PublishOutboxEvents;
import uz.hamkorbank.commhub.application.port.in.command.PublishOutboxEventsCommand;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.support.HubConfiguration;
import uz.hamkorbank.commhub.support.HubTestContainers;

/**
 * The database and the broker going away, and what that costs (QA-06, AD-03, NF-05).
 *
 * <p>The two outages are not symmetrical, and the whole point of testing them together is that they are
 * not. <b>Without the database an accepted message would be a lie</b>: the submission and its outbox
 * event share one transaction, so a submission that cannot commit must fail loudly, and the pod must
 * take itself out of the load balancer (readiness includes {@code db}, NF-05). <b>Without the broker
 * nothing is lost at all</b>: that is the entire reason the outbox exists — ingest keeps committing,
 * the events pile up unpublished with their error on the row, and the next successful pass sends them
 * in order.
 *
 * <p>Both are done with {@code docker pause} rather than a stub, because the failure a stub cannot
 * reproduce is the one that matters here: a connection that hangs instead of being refused. The
 * timeouts below are what turns it back into a failed request instead of a stuck thread.
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "commhub.outbox.relay.poll-interval-ms=3600000",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h",
            "commhub.kafka.outbound.create-topics=true",
            // Брокеру, которого нет, ждём недолго: relay держит строки заблокированными всё это время.
            "commhub.kafka.outbound.send-timeout=5s",
            // Замороженная БД не отвечает и не отказывает — предел ожидания задаётся здесь.
            "spring.datasource.hikari.connection-timeout=3000",
            "spring.datasource.hikari.validation-timeout=2000",
            "spring.datasource.hikari.maximum-pool-size=4",
            "commhub.rest.rate-limit.enabled=false"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InfrastructureOutageIT {

    private final JdbcClient jdbc;
    private final ProviderConfigRepository providers;
    private final StreamRepository streams;
    private final PublishOutboxEvents relay;
    private final RestClient rest;

    InfrastructureOutageIT(
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
        // Драйверные таймауты: без них запрос к замороженной БД висит, пока висит контейнер.
        registry.add("spring.datasource.url", () -> HubTestContainers.jdbcUrlWith("connectTimeout=2&socketTimeout=3"));
    }

    @BeforeEach
    void configureTheContour() {
        if (jdbc.sql("SELECT count(*) FROM stream").query(Integer.class).single() == 0) {
            HubConfiguration.seed(providers, streams);
        }
    }

    @AfterEach
    void resumeEverything() {
        // Контейнеры общие для всего JVM-прогона: оставленный на паузе повесил бы следующий класс.
        HubTestContainers.unpause(HubTestContainers.POSTGRES);
        HubTestContainers.unpause(HubTestContainers.KAFKA_BROKER);
    }

    @Test
    @DisplayName("QA-06: with the database down a submission is refused, not silently accepted (AD-03)")
    void databaseOutageRefusesSubmissionsAndRecovers() {
        // Arrange
        String externalId = "outage-" + UUID.randomUUID();

        // Act — база замирает под нагрузкой приёма
        HubTestContainers.pause(HubTestContainers.POSTGRES);

        // Assert — отказ, а не 202: непринятая транзакция — это непринятое сообщение
        assertThatThrownBy(() -> submit(externalId)).isInstanceOf(RuntimeException.class);
        assertThat(readinessIsUp())
                .as("readiness takes the pod out of the balancer (NF-05)")
                .isFalse();

        // Act — база возвращается
        HubTestContainers.unpause(HubTestContainers.POSTGRES);

        // Assert — приём восстанавливается, и от отказавшей попытки не осталось следа
        Map<?, ?> accepted = submit("recovered-" + externalId);
        assertThat(accepted.get("messageId")).isNotNull();
        assertThat(rowsWithExternalId(externalId))
                .as("a failed submission leaves nothing behind — the transaction rolled back")
                .isZero();
    }

    @Test
    @DisplayName("QA-06: with the broker down ingest keeps working and the outbox keeps the events (AD-03)")
    void brokerOutageDelaysPublicationWithoutLosingIt() {
        // Arrange
        jdbc.sql("DELETE FROM outbox_event").update();
        HubTestContainers.pause(HubTestContainers.KAFKA_BROKER);

        // Act — приём продолжается: ради этого outbox и существует
        Map<?, ?> accepted = submit("broker-" + UUID.randomUUID());
        OutboxRelayResult duringOutage = relay.publish(PublishOutboxEventsCommand.defaults());

        // Assert — событие осталось на строке вместе с причиной, published_at не проставлен
        assertThat(accepted.get("messageId")).isNotNull();
        assertThat(duringOutage.published()).isZero();
        assertThat(duringOutage.failed()).isPositive();
        assertThat(unpublished()).isPositive();
        assertThat(lastError()).isNotBlank();

        // Act — брокер возвращается
        HubTestContainers.unpause(HubTestContainers.KAFKA_BROKER);
        OutboxRelayResult afterRecovery = relay.publish(PublishOutboxEventsCommand.defaults());

        // Assert — всё накопленное уехало, ничего не потеряно
        assertThat(afterRecovery.published()).isPositive();
        assertThat(unpublished()).isZero();
    }

    private Map<?, ?> submit(String externalId) {
        String body = """
                {
                  "schemaVersion": "1.0",
                  "streamId": "%s",
                  "externalMessageId": "%s",
                  "recipient": { "msisdn": "998901234567" },
                  "content": { "sms": { "text": "Kod: 483920" } }
                }
                """.formatted(HubConfiguration.OTP_STREAM.value(), externalId);
        return rest.post()
                .uri("/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    private boolean readinessIsUp() {
        try {
            Map<?, ?> health =
                    rest.get().uri("/actuator/health/readiness").retrieve().body(Map.class);
            return "UP".equals(health.get("status"));
        } catch (RuntimeException e) {
            // 503 — это и есть «не готов»: индикатор БД не смог ответить (NF-05)
            return false;
        }
    }

    private int rowsWithExternalId(String externalId) {
        return jdbc.sql("SELECT count(*) FROM message WHERE external_id = :external")
                .param("external", externalId)
                .query(Integer.class)
                .single();
    }

    private int unpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Integer.class)
                .single();
    }

    private String lastError() {
        return jdbc.sql("SELECT last_error FROM outbox_event WHERE published_at IS NULL ORDER BY created_at LIMIT 1")
                .query(String.class)
                .single();
    }
}
