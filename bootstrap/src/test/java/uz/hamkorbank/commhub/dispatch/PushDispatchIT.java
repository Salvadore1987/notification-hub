package uz.hamkorbank.commhub.dispatch;

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
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.MessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.support.HubTestContainers;

/**
 * A push message walked end to end by the real dispatcher (PU-04, PU-08, PU-09, PU-12).
 *
 * <p>The one thing no other test could see. {@code PushFanOutTest} builds the fan-out by hand and
 * {@code PushDeliveryPersistenceIT} writes its rows inside a test transaction, so both are blind to the
 * question this asks: <b>is there a transaction at all when the fan-out writes?</b> Since the saga was
 * split around the provider call (ADR-0039) there is none — the call happens between
 * {@code DispatchPreparation} and {@code DispatchSettlement} — and the fan-out's writes are
 * {@code MANDATORY}. Push was therefore dead on the sending path: every message failed with an
 * {@code ADAPTER_ERROR} naming the missing transaction, retried, failed over and ended in the DLQ,
 * while not one device row was written and not one dead token was ever retired. Found by hand
 * (`IT-PRV-301`, прогон 14.08.2026), fixed by {@code PushDeliveryJournal}, and pinned here.
 *
 * <p>The fake provider stands in for the platforms (ADR-0041): its rule is the last two characters of
 * the token, so one device is accepted and one is refused as unregistered without stubbing anything.
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "commhub.outbox.relay.poll-interval-ms=3600000",
            // Диспетчер настоящий и включён намеренно: предмет теста — путь без транзакции вокруг
            // вызова провайдера, и создаёт его именно планировщик, а не тест.
            "commhub.dispatch.transactional.poll-interval=200ms",
            "commhub.dispatch.expiry.enabled=false",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h",
            "commhub.config.cache.refresh-interval=1s",
            "commhub.provider.mock.enabled=true",
            "commhub.provider.mock.latency=0ms",
            "commhub.kafka.outbound.create-topics=true",
            "commhub.rest.rate-limit.enabled=false"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PushDispatchIT {

    private static final StreamId PUSH_STREAM = StreamId.of("push-dispatch-it");
    private static final ProviderCode PUSH_PROVIDER = ProviderCode.of("MOCK_PUSH_IT");

    /** Правило фиктивного провайдера — последние два символа адреса (ADR-0041). */
    private static final String LIVE_TOKEN = "device-live-00";

    private static final String DEAD_TOKEN = "device-dead-04";

    private final JdbcClient jdbc;
    private final ProviderConfigRepository providers;
    private final StreamRepository streams;
    private final RestClient rest;

    PushDispatchIT(
            JdbcClient jdbc,
            ProviderConfigRepository providers,
            StreamRepository streams,
            @Value("${local.server.port}") int port) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.streams = streams;
        this.rest = RestClient.create("http://localhost:" + port);
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        HubTestContainers.register(registry);
    }

    /**
     * A push contour of its own, and a routing policy that outranks whatever else the shared database
     * holds: the container lives for the whole module, and a global "everything to SMS" policy left by a
     * neighbouring test would route this message to a channel with no devices.
     */
    @BeforeEach
    void configureThePushContour() {
        Provider provider = providers.save(Provider.register(
                ProviderId.newId(),
                PUSH_PROVIDER,
                Channel.PUSH,
                AdapterType.of("mock-push"),
                new Provider.Settings(10, Tariff.perMessage(Money.of("1.0000", "UZS")), RateLimit.unlimited(), true)));
        ChannelConfig push = ChannelConfig.of(Channel.PUSH, BalancingStrategy.PRIMARY_ONLY);
        push.updateFallbackOrder(List.of(provider.ref()));
        providers.save(push);
        providers.save(RoutingPolicy.of(
                RoutingPolicyId.newId(),
                RoutingPolicy.Match.ofStream(PUSH_STREAM),
                RoutingPolicy.Action.toChannel(Channel.PUSH),
                20));
        streams.save(Stream.register(
                PUSH_STREAM, "Push dispatch", Stream.Defaults.of(Channel.PUSH, TrafficClass.TRANSACTIONAL)));
        jdbc.sql("DELETE FROM push_delivery").update();
        jdbc.sql("DELETE FROM suppression_list WHERE reason = 'PUSH_TOKEN_INVALID'")
                .update();
    }

    @Test
    @DisplayName("PU-09: the dispatcher's fan-out writes a row per device and retires the dead token")
    void aDispatchedPushMessageIsRecordedPerDevice() {
        // Arrange & Act — одно сообщение на два устройства, дальше работает планировщик
        MessageId messageId = submitPush();

        // Assert — принято, потому что живое устройство приняло (PU-09), и терминально здесь (PU-12)
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(statusOf(messageId)).isEqualTo("SENT_TO_PROVIDER"));

        assertThat(count("SELECT count(*) FROM delivery_attempt WHERE message_id = '%s'".formatted(messageId.value())))
                .as("веер живёт над портом: сага видит одну попытку (AR-05)")
                .isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT attempt_id) FROM push_delivery WHERE message_id = '%s'"
                        .formatted(messageId.value())))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM push_delivery WHERE message_id = '%s'".formatted(messageId.value())))
                .as("по строке на каждое устройство (PU-09)")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM push_delivery WHERE message_id = '%s' AND token_invalidated"
                        .formatted(messageId.value())))
                .isEqualTo(1);

        // Два последствия мёртвого токена, и оба обязательны (PU-04, PU-08)
        assertThat(count("SELECT count(*) FROM suppression_list WHERE reason = 'PUSH_TOKEN_INVALID'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'PUSH_TOKEN_INVALIDATED'"))
                .isPositive();
    }

    private MessageId submitPush() {
        Map<String, Object> body = Map.of(
                "streamId", PUSH_STREAM.value(),
                "externalMessageId", "push-" + UUID.randomUUID(),
                "recipient",
                        Map.of(
                                "clientId",
                                "CL-PUSH",
                                "pushTokens",
                                List.of(
                                        Map.of("platform", "ANDROID", "token", LIVE_TOKEN),
                                        Map.of("platform", "ANDROID", "token", DEAD_TOKEN))),
                "content", Map.of("push", Map.of("title", "Hamkorbank", "body", "Push dispatch")));
        Map<?, ?> accepted = rest.post()
                .uri("/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return MessageId.of(UUID.fromString(String.valueOf(accepted.get("messageId"))));
    }

    private String statusOf(MessageId messageId) {
        return jdbc.sql("SELECT status FROM message WHERE id = :id")
                .param("id", messageId.value())
                .query(String.class)
                .single();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
