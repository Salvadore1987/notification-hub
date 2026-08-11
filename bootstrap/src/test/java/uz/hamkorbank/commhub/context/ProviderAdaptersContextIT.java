package uz.hamkorbank.commhub.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestPropertySource;
import uz.hamkorbank.commhub.adapter.in.callback.ProviderCallbackTranslator;
import uz.hamkorbank.commhub.application.port.in.GetDeployedAdapters;
import uz.hamkorbank.commhub.application.port.out.provider.EmailProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.PushProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.SmsProviderPort;
import uz.hamkorbank.commhub.bootstrap.NotificationHubApplication;
import uz.hamkorbank.commhub.support.HubTestContainers;

/**
 * The same context, with every provider adapter of §9 switched on (QA-03, AR-04).
 *
 * <p>{@link ApplicationContextIT} starts the Hub as a developer machine starts it: no provider enabled,
 * because none of them has credentials there. That is also the state in which an adapter that cannot be
 * constructed stays invisible — and the contour where it is first constructed is production, on the
 * evening it was enabled.
 *
 * <p>So this context enables all five, with credentials that resolve to nothing. Nothing here calls a
 * provider: the adapters resolve their secrets per call (SEC-04) and open their connections on demand,
 * which is exactly why they can be built without any of that being real.
 */
@Tag("integration")
@SpringBootTest(classes = NotificationHubApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(
        properties = {
            // Предмет теста — адаптеры §9. Фиктивный провайдер стенда сюда не относится
            // и включён локальным config/application.yml, который читается и отсюда (ADR-0041).
            "commhub.provider.mock.enabled=false",
            "commhub.outbox.relay.poll-interval-ms=3600000",
            "commhub.config.cache.refresh-interval=30s",
            "commhub.provider.health.initial-delay=1h",
            "commhub.metrics.backlog-refresh-interval=1h",
            // §9.1 Playmobile
            "commhub.provider.playmobile.enabled=true",
            "commhub.provider.playmobile.sending.originator=3700",
            // §9.2 SMS Gate, with the reconciliation of SG-03 on
            "commhub.provider.smsgate.enabled=true",
            "commhub.provider.smsgate.sending.sender=HAMKORBANK",
            "commhub.provider.smsgate.reconciliation.enabled=true",
            "commhub.provider.smsgate.reconciliation.interval=1h",
            // §9.3 corporate SMTP, with the bounce poller of EM-02 on
            "commhub.provider.smtp.enabled=true",
            "commhub.provider.smtp.server.host=localhost",
            "commhub.provider.smtp.server.port=2525",
            "commhub.provider.smtp.server.security=NONE",
            "commhub.provider.smtp.sending.return-path=bounces@hamkorbank.uz",
            "commhub.provider.smtp.bounce.enabled=true",
            "commhub.provider.smtp.bounce.host=localhost",
            "commhub.provider.smtp.bounce.port=1143",
            "commhub.provider.smtp.bounce.ssl=false",
            "commhub.provider.smtp.bounce.settings.interval=1h",
            // §9.4.1 FCM
            "commhub.provider.fcm.enabled=true",
            // §9.4.2 APNs
            "commhub.provider.apns.enabled=true",
            "commhub.provider.apns.credentials.team-id=TEAMID1234",
            "commhub.provider.apns.credentials.key-id=KEYID12345",
            "commhub.provider.apns.sending.topic=uz.hamkorbank.mobile",
            // FR-6.4: the mart export, off in the defaults
            "commhub.export.events.enabled=true",
            "commhub.export.events.interval=1h"
        })
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ProviderAdaptersContextIT {

    private final ApplicationContext context;
    private final GetDeployedAdapters adapters;

    ProviderAdaptersContextIT(ApplicationContext context, GetDeployedAdapters adapters) {
        this.context = context;
        this.adapters = adapters;
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        HubTestContainers.register(registry);
    }

    @Test
    @DisplayName("MP-05: both SMS adapters are wired, each under its own adapter type")
    void smsAdaptersAreWired() {
        // Arrange + Act
        List<String> types = adapterTypesOf(SmsProviderPort.class);

        // Assert
        assertThat(types).containsExactlyInAnyOrder("playmobile-http", "smsgate-http");
    }

    @Test
    @DisplayName("EM-01: the SMTP adapter is wired, pool and DKIM signer included")
    void emailAdapterIsWired() {
        // Arrange + Act
        List<String> types = adapterTypesOf(EmailProviderPort.class);

        // Assert
        assertThat(types).containsExactly("smtp");
    }

    @Test
    @DisplayName("PU-01: both push adapters are wired")
    void pushAdaptersAreWired() {
        // Arrange + Act
        List<String> types = adapterTypesOf(PushProviderPort.class);

        // Assert
        assertThat(types).containsExactlyInAnyOrder("fcm-http", "apns-http2");
    }

    @Test
    @DisplayName("AR-04, §11.2: the provider form is offered exactly the adapters this contour deployed")
    void deployedAdaptersAreOfferedToTheProviderForm() {
        // Arrange + Act — the same question the panel asks, through the use case behind the endpoint
        List<String> offered = adapters.adapters().stream()
                .map(view -> view.adapterType().value() + "/" + view.channel().name())
                .toList();

        // Assert — every adapter wired above, with the channel a profile has to name alongside it
        assertThat(offered)
                .containsExactly(
                        "smtp/EMAIL", "apns-http2/PUSH", "fcm-http/PUSH", "playmobile-http/SMS", "smsgate-http/SMS");
    }

    @Test
    @DisplayName("PM-02, SG-02: an enabled SMS provider brings its callback translator with it (AR-04)")
    void callbackTranslatorsComeWithTheirProviders() {
        // Arrange + Act
        String[] translators = context.getBeanNamesForType(ProviderCallbackTranslator.class);

        // Assert — status vocabulary lives next to error vocabulary, in the provider's own package
        assertThat(translators).hasSize(2);
    }

    /** The adapter types as the routing configuration names them ({@code provider.adapter_type}). */
    private List<String> adapterTypesOf(Class<? extends ProviderPort> portType) {
        return context.getBeansOfType(portType).values().stream()
                .map(port -> port.adapterType().value())
                .sorted()
                .toList();
    }
}
