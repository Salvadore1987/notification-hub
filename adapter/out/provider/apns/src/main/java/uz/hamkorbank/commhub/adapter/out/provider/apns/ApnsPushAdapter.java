package uz.hamkorbank.commhub.adapter.out.provider.apns;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.adapter.out.provider.support.Blobs;
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
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The Apple Push Notification service integration (§9.4.2, PU-06…PU-08, PU-13).
 *
 * <p>iOS only, and the direct path — the alternative is FCM relaying to APNs on the Bank's behalf
 * (PU-05), which is the same channel with one more party in it. Both adapters can be deployed together:
 * the router picks between them per message, and switching is a configuration change (FR-2.2, AD-07).
 *
 * <p><b>HTTP/2 comes from the JDK client</b> (PU-07). One request per notification, but hundreds of them
 * multiplexed over a handful of connections rather than a connection each; when Apple sends
 * {@code GOAWAY} — which it does routinely, to rebalance — the client drains that connection and opens
 * another without the adapter noticing. Writing a connection pool here would mean re-implementing what
 * {@link java.net.http.HttpClient} already does, and doing it worse: the interesting part of PU-07 is
 * that the notification path never blocks on a connection, and virtual threads plus multiplexing is
 * exactly that (AR-07).
 *
 * <p><b>{@code apns-id} is the message id.</b> The Hub's identifiers are UUIDv7, which is the format
 * Apple wants, so the notification carries the Hub's own id end to end: a support question about "this
 * notification" is answerable in both directions, and a repeat of the same request is recognised by
 * Apple as the same notification rather than a second alert.
 *
 * <p><b>Sandbox is chosen per message, not per deployment</b> (PU-13). A token from a development or
 * TestFlight build only exists on Apple's sandbox, and a test send (FR-7.4) is precisely how one is
 * exercised; a production token on sandbox — and the reverse — is refused as {@code BadDeviceToken},
 * which is why the two never share a host.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.apns", name = "enabled", havingValue = "true")
public class ApnsPushAdapter implements PushProviderPort {

    /** Value of {@code provider.adapter_type} this bean serves (§10.1, AR-04). */
    public static final String ADAPTER_TYPE = "apns-http2";

    public static final String THROTTLED_CODE = "THROTTLED";

    static final String HEADER_ID = "apns-id";
    static final String HEADER_TOPIC = "apns-topic";
    static final String HEADER_PUSH_TYPE = "apns-push-type";
    static final String HEADER_PRIORITY = "apns-priority";
    static final String HEADER_EXPIRATION = "apns-expiration";
    static final String HEADER_COLLAPSE_ID = "apns-collapse-id";
    static final String HEADER_AUTHORIZATION = "authorization";

    /** Immediate delivery; PU-06 reserves it for critical and transactional traffic. */
    private static final String PRIORITY_IMMEDIATE = "10";

    /** Power-considerate: Apple may hold the notification back to save battery — right for bulk. */
    private static final String PRIORITY_CONSIDERATE = "5";

    /** {@code apns-collapse-id} is capped at 64 bytes by Apple. */
    private static final int MAX_COLLAPSE_ID_LENGTH = 64;

    private static final Logger LOG = LoggerFactory.getLogger(ApnsPushAdapter.class);

    private final ApnsProperties properties;
    private final ApnsMessageCodec codec;
    private final ProviderCallExecutor executor;
    private final ProviderThrottle throttle;
    private final ClockPort clock;
    private final ProviderRuntimeSettings runtimeSettings;
    private final RestClient production;
    private final RestClient sandbox;
    private final ApnsJwtProvider providerTokens;

    public ApnsPushAdapter(ApnsProperties properties, ApnsMessageCodec codec, ProviderSupport support) {
        this.properties = Guard.notNull(properties, "properties");
        this.codec = Guard.notNull(codec, "codec");
        Guard.notNull(support, "support");
        this.executor = support.executor();
        this.throttle = support.throttle();
        this.clock = support.clock();
        this.runtimeSettings = support.runtimeSettings();
        this.production = support.clients().create(properties.http());
        this.sandbox = support.clients().create(properties.sandbox());
        this.providerTokens = new ApnsJwtProvider(properties.credentials());
    }

    @Override
    public AdapterType adapterType() {
        return AdapterType.of(ADAPTER_TYPE);
    }

    /** iOS only: an Android token has nothing to do with Apple, whatever the routing says. */
    @Override
    public boolean supportsPlatform(PushPlatform platform) {
        return platform == PushPlatform.IOS;
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
        ApnsProperties.Sending sending = sending();
        if (!sending.hasTopic()) {
            throw ProviderCallException.blocking(
                    "NO_TOPIC", "no apns-topic (bundle id) is configured for APNs (PU-06)");
        }
        Instant now = clock.now();
        String payload = codec.encode(submission);
        RestClient client = submission.context().test() ? sandbox : production;
        RestClient.RequestBodySpec request = client.post()
                .uri(ApnsProperties.SEND_PATH_TEMPLATE.formatted(
                        submission.token().value()))
                .header(HEADER_AUTHORIZATION, "bearer " + providerToken(now))
                .header(HEADER_ID, submission.messageId().value().toString())
                .header(HEADER_TOPIC, sending.topic())
                .header(HEADER_PUSH_TYPE, sending.pushType())
                .header(HEADER_PRIORITY, priorityOf(submission.context().trafficClass()))
                .contentType(MediaType.APPLICATION_JSON);
        expirationOf(submission, sending, now).ifPresent(value -> request.header(HEADER_EXPIRATION, value));
        collapseIdOf(submission).ifPresent(value -> request.header(HEADER_COLLAPSE_ID, value));
        ProviderHttpResponse response = ProviderRestClients.send(request.body(payload));
        if (response.isSuccess()) {
            // Терминальный статус push — SENT_TO_PROVIDER: отчёта о показе APNs не присылает (PU-12).
            return ProviderAck.accepted(
                    ProviderMessageId.of(submission.messageId().value().toString()),
                    String.valueOf(response.status()),
                    clock.now());
        }
        return refused(response);
    }

    /**
     * Apple refused the notification.
     *
     * <p>Only a refusal that is about APNs is thrown: its own 5xx and a rejection of the Hub's provider
     * token. A dead device and a malformed payload come back as answers, so ten thousand uninstalled
     * applications never open the breaker of a healthy integration (PR-01, PU-08).
     */
    private ProviderAck refused(ProviderHttpResponse response) {
        String reason = codec.reasonOf(response.body());
        int status = response.status();
        String description = ApnsResponseCatalog.describe(reason, status);
        if (ApnsResponseCatalog.invalidatesProviderToken(reason)) {
            // Токен мог просто устареть (перезапуск, сдвиг часов): следующее сообщение должно нести
            // свежую подпись, иначе весь iOS-трафик будет отказывать до конца окна ротации (PU-06).
            providerTokens.invalidate();
        }
        ErrorClass errorClass = ApnsResponseCatalog.classify(reason, status);
        if (ApnsResponseCatalog.countsAsProviderFailure(reason, status)) {
            throw errorClass == ErrorClass.BLOCKING
                    ? ProviderCallException.blocking(reason, description)
                    : ProviderCallException.retryable(String.valueOf(status), description);
        }
        ProviderAck ack = errorClass == ErrorClass.NON_RETRYABLE
                ? ProviderAck.rejected(reasonCode(reason), description, clock.now())
                : ProviderAck.failed(reasonCode(reason), errorClass, description, clock.now());
        return ApnsResponseCatalog.invalidatesToken(reason, status) ? ack.withInvalidRecipient() : ack;
    }

    /** {@code apns-priority}: 10 immediate for OTP and transactional, 5 power-considerate for bulk. */
    private static String priorityOf(TrafficClass trafficClass) {
        return switch (trafficClass) {
            case CRITICAL_OTP, TRANSACTIONAL -> PRIORITY_IMMEDIATE;
            case NOTIFICATION -> PRIORITY_CONSIDERATE;
        };
    }

    /**
     * {@code apns-expiration}: the absolute second after which Apple stops trying (PU-06).
     *
     * <p>Absent rather than zero when the message has no deadline: zero means "deliver once or discard",
     * which is a stronger statement than "no TTL was given" and would quietly drop notifications to a
     * phone that happens to be off.
     */
    private static Optional<String> expirationOf(
            PushSubmission submission, ApnsProperties.Sending sending, Instant now) {
        Optional<Instant> deadline = submission.timing().expiresAt(now);
        if (deadline.isPresent()) {
            return deadline.map(instant -> String.valueOf(instant.getEpochSecond()));
        }
        Duration defaultTtl = sending.defaultTtl();
        return defaultTtl == null
                ? Optional.empty()
                : Optional.of(String.valueOf(now.plus(defaultTtl).getEpochSecond()));
    }

    private static Optional<String> collapseIdOf(PushSubmission submission) {
        String collapseKey = submission.collapseKey();
        if (collapseKey == null || collapseKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(
                collapseKey.length() <= MAX_COLLAPSE_ID_LENGTH
                        ? collapseKey
                        : collapseKey.substring(0, MAX_COLLAPSE_ID_LENGTH));
    }

    /** {@code delivery_attempt.response_code} is {@code varchar(64)} and Apple's reasons are short. */
    private static String reasonCode(String reason) {
        return reason == null || reason.isBlank() ? ApnsResponseCatalog.UNSPECIFIED : reason.trim();
    }

    private Optional<ProviderAck> throttleVerdict(PushSubmission submission) {
        Optional<String> refusal = throttle.acquire(
                properties.providerCode(),
                runtimeSettings.rateLimitOf(properties.providerCode(), properties.rateLimit()),
                submission.token().value());
        if (refusal.isEmpty()) {
            return Optional.empty();
        }
        LOG.info("APNs send held back for {}: {}", submission.token().masked(), refusal.get());
        return Optional.of(ProviderAck.failed(THROTTLED_CODE, ErrorClass.RETRYABLE, refusal.get(), clock.now()));
    }

    /** Sending settings with {@code provider.endpoint_config} applied (AD-07). */
    private ApnsProperties.Sending sending() {
        return properties.sending().overlay(runtimeSettings.endpointConfigOf(properties.providerCode()));
    }

    /** The signed provider token, built from the deployment settings (SEC-04, ADR-0044). */
    private String providerToken(Instant now) {
        ApnsProperties.Credentials credentials = properties.credentials();
        if (!credentials.isConfigured()) {
            throw ProviderCallException.blocking(
                    "NO_CREDENTIALS", "no team id, key id and .p8 key are configured for APNs (PU-06)");
        }
        return providerTokens.tokenAt(Blobs.decodeIfBase64(credentials.privateKey()), now);
    }
}
