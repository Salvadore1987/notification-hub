package uz.hamkorbank.commhub.adapter.out.provider.fcm;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import uz.hamkorbank.commhub.adapter.out.provider.support.Blobs;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpResponse;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRuntimeSettings;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderSupport;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderThrottle;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.PushProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.PushSubmission;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The Firebase Cloud Messaging integration (§9.4.1, PU-01…PU-05).
 *
 * <p>One notification per request, as HTTP v1 requires (PU-02): the throughput of a bulk push comes
 * from doing many of these at once on virtual threads, not from a batch endpoint Google no longer
 * offers. The fan-out that drives that concurrency lives above the port, in the application layer,
 * because it is the same for both platforms (PU-09, PU-10).
 *
 * <p><b>Which platforms it serves</b> is not fixed. Android and Web always; iOS only in the PU-05 mode,
 * where the Bank's application embeds the Firebase SDK and FCM relays to APNs itself. The switch is read
 * from {@code provider.endpoint_config} on every call, so moving iOS traffic between FCM and the direct
 * APNs adapter is a configuration change applied within the refresh window rather than a deploy
 * (AD-07, FR-2.7) — which is what an operator needs at the moment one of the two is failing.
 *
 * <p><b>Authentication</b> is an OAuth2 access token derived from the service account key (PU-01);
 * {@link FcmAccessTokens} keeps it fresh. A refusal of the credentials drops the cached token as well as
 * opening the breaker: an expired token then costs one message rather than an hour of them.
 *
 * <p>Retry inside a delivery attempt is enabled, unlike SMS Gate. It is safe here because only calls
 * that produced <em>no answer</em> are retried (PR-01), and FCM's transient codes state plainly that the
 * message was not accepted; a duplicate notification is not on the table.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.fcm", name = "enabled", havingValue = "true")
public class FcmPushAdapter implements PushProviderPort {

    /** Value of {@code provider.adapter_type} this bean serves (§10.1, AR-04). */
    public static final String ADAPTER_TYPE = "fcm-http";

    public static final String THROTTLED_CODE = "THROTTLED";

    private static final int LOGGED_BODY_LENGTH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(FcmPushAdapter.class);

    private final FcmProperties properties;
    private final FcmMessageCodec codec;
    private final FcmJson json;
    private final ProviderCallExecutor executor;
    private final ProviderThrottle throttle;
    private final ClockPort clock;
    private final ProviderRuntimeSettings runtimeSettings;
    private final RestClient client;
    private final FcmAccessTokens accessTokens;

    public FcmPushAdapter(FcmProperties properties, FcmMessageCodec codec, FcmJson json, ProviderSupport support) {
        this.properties = Guard.notNull(properties, "properties");
        this.codec = Guard.notNull(codec, "codec");
        this.json = Guard.notNull(json, "json");
        Guard.notNull(support, "support");
        this.executor = support.executor();
        this.throttle = support.throttle();
        this.clock = support.clock();
        this.runtimeSettings = support.runtimeSettings();
        this.client = support.clients().create(properties.http());
        this.accessTokens = new FcmAccessTokens(
                support.clients(),
                properties.oauth().tokenUrl(),
                properties.oauth().refreshSkew());
    }

    @Override
    public AdapterType adapterType() {
        return AdapterType.of(ADAPTER_TYPE);
    }

    /**
     * Android and Web always, iOS only in the PU-05 mode.
     *
     * <p>Read per call rather than cached in a field: the mode is a routing decision and has to apply
     * within the configuration refresh window (AD-07). The read itself is a map lookup — the runtime
     * settings are cached (NF-07).
     */
    @Override
    public boolean supportsPlatform(PushPlatform platform) {
        return switch (platform) {
            case ANDROID, WEB -> true;
            case IOS -> sending().iosDelivery();
        };
    }

    @Override
    public ProviderAck submit(PushSubmission submission) {
        Guard.notNull(submission, "submission");
        Optional<ProviderAck> held = throttleVerdict(submission);
        if (held.isPresent()) {
            return held.get();
        }
        return executor.execute(properties.providerCode(), properties.resilience(), () -> call(submission));
    }

    private ProviderAck call(PushSubmission submission) {
        FcmServiceAccount account = serviceAccount();
        boolean validateOnly =
                submission.context().test() && properties.sending().validateTestSends();
        String document = codec.encode(submission, sending(), validateOnly, clock.now());
        ProviderHttpResponse response = ProviderRestClients.send(client.post()
                .uri(account.sendPath())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokens.tokenFor(account, clock.now()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(document));
        if (response.isSuccess()) {
            return accepted(response, validateOnly);
        }
        return refused(response);
    }

    /**
     * FCM accepted the notification (PU-12).
     *
     * <p>{@code name} is the platform's own identifier of this delivery and is kept: it is the only
     * handle an operator has when Google support asks which message is meant. It is not a delivery
     * receipt — neither platform sends one — so the message is terminal at {@code SENT_TO_PROVIDER}.
     */
    private ProviderAck accepted(ProviderHttpResponse response, boolean validateOnly) {
        JsonNode body = json.readOrNull(response.body());
        String name = FcmJson.scalar(body, "name").orElse(null);
        if (validateOnly) {
            LOG.debug("FCM validated a test send (PU-13), nothing was delivered");
        }
        return ProviderAck.accepted(
                name == null ? null : ProviderMessageId.of(truncate(name)),
                String.valueOf(response.status()),
                clock.now());
    }

    /**
     * FCM refused the notification.
     *
     * <p>Only what says something about FCM is thrown; a dead token or a malformed message is returned,
     * so a campaign against uninstalled applications never opens a breaker (PR-01, PU-04).
     */
    private ProviderAck refused(ProviderHttpResponse response) {
        String errorCode = errorCodeOf(response);
        int status = response.status();
        String description = FcmErrorCatalog.describe(errorCode, status, messageOf(response));
        if (FcmErrorCatalog.invalidatesAccessToken(errorCode, status)) {
            // Возможно, токен просто истёк раньше срока (отзыв ключа, сдвиг часов) — следующий вызов
            // должен получить новый, иначе провайдер будет отказывать до конца часа жизни токена.
            accessTokens.invalidate();
        }
        ErrorClass errorClass = FcmErrorCatalog.classify(errorCode, status);
        if (FcmErrorCatalog.countsAsProviderFailure(errorCode, status)) {
            throw errorClass == ErrorClass.BLOCKING
                    ? ProviderCallException.blocking(errorCode, description)
                    : ProviderCallException.retryable(errorCode, description);
        }
        ProviderAck ack = errorClass == ErrorClass.NON_RETRYABLE
                ? ProviderAck.rejected(errorCode, description, clock.now())
                : ProviderAck.failed(errorCode, errorClass, description, clock.now());
        return FcmErrorCatalog.invalidatesToken(errorCode, status) ? ack.withInvalidRecipient() : ack;
    }

    /** {@code error.details[].errorCode}, which is where HTTP v1 puts the code that matters (PU-04). */
    private String errorCodeOf(ProviderHttpResponse response) {
        JsonNode error = errorNode(response);
        if (error == null) {
            return null;
        }
        JsonNode details = error.get("details");
        if (details != null && details.isArray()) {
            for (JsonNode detail : details) {
                Optional<String> code = FcmJson.scalar(detail, "errorCode");
                if (code.isPresent()) {
                    return code.get();
                }
            }
        }
        return FcmJson.scalar(error, "status").orElse(null);
    }

    private String messageOf(ProviderHttpResponse response) {
        JsonNode error = errorNode(response);
        return error == null
                ? Masking.body(response.body(), LOGGED_BODY_LENGTH)
                : FcmJson.scalar(error, "message").orElse(null);
    }

    private JsonNode errorNode(ProviderHttpResponse response) {
        JsonNode body = json.readOrNull(response.body());
        return body == null ? null : body.get("error");
    }

    private Optional<ProviderAck> throttleVerdict(PushSubmission submission) {
        Optional<String> refusal = throttle.acquire(
                properties.providerCode(),
                runtimeSettings.rateLimitOf(properties.providerCode(), properties.rateLimit()),
                submission.token().value());
        if (refusal.isEmpty()) {
            return Optional.empty();
        }
        LOG.info("FCM send held back for {}: {}", submission.token().masked(), refusal.get());
        return Optional.of(ProviderAck.failed(THROTTLED_CODE, ErrorClass.RETRYABLE, refusal.get(), clock.now()));
    }

    /** Sending settings with {@code provider.endpoint_config} applied (AD-07, PU-05). */
    private FcmProperties.Sending sending() {
        return properties.sending().overlay(runtimeSettings.endpointConfigOf(properties.providerCode()));
    }

    /**
     * The service account key, resolved per call so a rotation applies without a restart (SEC-04).
     *
     * <p>Parsed every time rather than cached: parsing a few kilobytes of JSON is not what costs anything
     * on this path, while a cached parse would keep a revoked key alive for as long as the process runs.
     */
    private FcmServiceAccount serviceAccount() {
        if (!properties.hasCredentials()) {
            throw ProviderCallException.blocking(
                    "NO_CREDENTIALS", "no service account key is configured for FCM (PU-01, SEC-04)");
        }
        return FcmServiceAccount.parse(
                json,
                Blobs.decodeIfBase64(properties.credentials().serviceAccount()),
                properties.oauth().tokenUrl());
    }

    /** {@code delivery_attempt.provider_message_id} is {@code varchar(64)}; FCM names are longer. */
    private static String truncate(String name) {
        return name.length() <= 64 ? name : name.substring(name.length() - 64);
    }
}
