package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateSendCodec.SmsGateAnswer;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateSendCodec.SmsGateItem;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpResponse;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRuntimeSettings;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderSupport;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderThrottle;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.SmsProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.SmsSubmission;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The SMS Gate v4.3 integration, reserve SMS provider of the MVP (§9.2, §18.2, SG-01…SG-04).
 *
 * <p>The same {@code SmsProviderPort} as Playmobile, and that is the point of AR-04: the router picks
 * between them by configuration, and nothing in the domain or the application layer distinguishes them.
 *
 * <p>Two behaviours differ from the primary adapter, both forced by the API:
 *
 * <ul>
 *   <li><b>No retry inside a delivery attempt.</b> {@code /api/v2/send} takes no client-supplied
 *       identifier, so a request that arrived but whose answer was lost cannot be recognised as a
 *       duplicate — a retry would be a second SMS to the customer. The saga's failover covers it
 *       instead (§9.2, PR-01).
 *   <li><b>The provider assigns the identifier.</b> {@code id} comes back in the answer and is what
 *       every FEEDBACK report is matched by, so it is put on the ack and overwrites the id the Hub
 *       generated for the attempt (§18.2, PM-02 for the mechanism).
 * </ul>
 *
 * <p>The per-recipient hourly ceiling is enforced before the call, not discovered from code 1: a
 * message the Hub holds back still has its failover, a message the provider refuses has already spent
 * an attempt (FR-2.5, §18.2).
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.smsgate", name = "enabled", havingValue = "true")
public class SmsGateSmsAdapter implements SmsProviderPort {

    /** Value of {@code provider.adapter_type} this bean serves (§10.1, AR-04). */
    public static final String ADAPTER_TYPE = "smsgate-http";

    public static final String THROTTLED_CODE = "THROTTLED";

    private static final int LOGGED_BODY_LENGTH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(SmsGateSmsAdapter.class);

    private final SmsGateProperties properties;
    private final SmsGateSendCodec codec;
    private final ProviderCallExecutor executor;
    private final ProviderThrottle throttle;
    private final SecretResolverPort secrets;
    private final ClockPort clock;
    private final ProviderRuntimeSettings runtimeSettings;
    private final RestClient client;

    public SmsGateSmsAdapter(SmsGateProperties properties, SmsGateSendCodec codec, ProviderSupport support) {
        this.properties = Guard.notNull(properties, "properties");
        this.codec = Guard.notNull(codec, "codec");
        Guard.notNull(support, "support");
        this.executor = support.executor();
        this.throttle = support.throttle();
        this.secrets = support.secrets();
        this.clock = support.clock();
        this.runtimeSettings = support.runtimeSettings();
        this.client = support.clients().create(properties.http());
    }

    @Override
    public AdapterType adapterType() {
        return AdapterType.of(ADAPTER_TYPE);
    }

    @Override
    public ProviderAck submit(SmsSubmission submission) {
        Guard.notNull(submission, "submission");
        Optional<ProviderAck> held = throttleVerdict(submission);
        if (held.isPresent()) {
            return held.get();
        }
        String document = codec.encodeSend(credentials(), submission, sending());
        return executor.execute(properties.providerCode(), properties.resilience(), () -> callSend(document));
    }

    /**
     * Hands a chunk to {@code /api/v2/send_msgs} (§9.2).
     *
     * <p>Unlike the primary provider, SMS Gate answers a chunk element by element, so the acks are
     * genuinely per message: one element may be accepted while its neighbour is refused as a duplicate
     * or a blacklisted number (§18.2, SG-02). Elements the throttle held back never enter the request
     * and get their own retryable ack in place.
     */
    @Override
    public List<ProviderAck> submitBatch(List<SmsSubmission> submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return List.of();
        }
        List<SmsSubmission> sendable = new ArrayList<>();
        List<ProviderAck> acks = new ArrayList<>();
        for (SmsSubmission submission : submissions) {
            Optional<ProviderAck> held = throttleVerdict(submission);
            acks.add(held.orElse(null));
            if (held.isEmpty()) {
                sendable.add(submission);
            }
        }
        if (sendable.isEmpty()) {
            return List.copyOf(acks);
        }
        String document = codec.encodeSendBatch(credentials(), sendable, sending());
        List<ProviderAck> sent = executor.executeBatch(
                properties.providerCode(),
                properties.resilience(),
                sendable.size(),
                () -> callSendBatch(document, sendable.size()));
        return merge(acks, sent);
    }

    private ProviderAck callSend(String document) {
        ProviderHttpResponse response = post(SmsGateProperties.SEND_PATH, document);
        SmsGateAnswer answer = codec.readAnswer(response.body()).orElseThrow(() -> unreadable(response));
        LOG.debug("SMS Gate /send answered code={} ({})", answer.code(), answer.description());
        if (answer.isSuccess()) {
            return ProviderAck.accepted(
                    answer.id() == null ? null : ProviderMessageId.of(answer.id()), answer.code(), clock.now());
        }
        return refused(answer.code(), answer.description());
    }

    /**
     * The chunk form, which answers per element (§9.2).
     *
     * <p>An answer without a {@code messages} array is a verdict on the whole request — bad
     * credentials, a malformed document — and every element of the chunk shares it.
     */
    private List<ProviderAck> callSendBatch(String document, int size) {
        ProviderHttpResponse response = post(SmsGateProperties.SEND_BATCH_PATH, document);
        List<SmsGateItem> items = codec.readBatchAnswer(response.body());
        if (items.isEmpty()) {
            SmsGateAnswer answer = codec.readAnswer(response.body()).orElseThrow(() -> unreadable(response));
            ProviderAck ack = answer.isSuccess()
                    ? ProviderAck.accepted(null, answer.code(), clock.now())
                    : refused(answer.code(), answer.description());
            return Collections.nCopies(size, ack);
        }
        return items.stream()
                .sorted(Comparator.comparingInt(SmsGateItem::seq))
                .map(this::ackOf)
                .toList();
    }

    private ProviderAck ackOf(SmsGateItem item) {
        if (item.isSuccess()) {
            return ProviderAck.accepted(
                    item.id() == null ? null : ProviderMessageId.of(item.id()), item.code(), clock.now());
        }
        ProviderAck ack = ProviderAck.failed(
                item.code(),
                SmsGateResponseCatalog.classify(item.code()),
                SmsGateResponseCatalog.describe(item.code()),
                clock.now());
        return SmsGateResponseCatalog.invalidatesRecipient(item.code()) ? ack.withInvalidRecipient() : ack;
    }

    /**
     * A refusal carried in {@code status.code} (§18.2).
     *
     * <p>Only the codes that say something about the provider are thrown: those are the ones retry and
     * the circuit breaker must see. The spam limit and every content refusal are <em>returned</em>, so
     * a busy number or a rejected text never counts against the provider's health
     * ({@link SmsGateResponseCatalog#countsAsProviderFailure(String)}).
     */
    private ProviderAck refused(String code, String description) {
        ErrorClass errorClass = SmsGateResponseCatalog.classify(code);
        if (SmsGateResponseCatalog.countsAsProviderFailure(code)) {
            throw errorClass == ErrorClass.BLOCKING
                    ? ProviderCallException.blocking(code, description)
                    : ProviderCallException.retryable(code, description);
        }
        ProviderAck ack = errorClass == ErrorClass.NON_RETRYABLE
                ? ProviderAck.rejected(code, description, clock.now())
                : ProviderAck.failed(code, errorClass, description, clock.now());
        return SmsGateResponseCatalog.invalidatesRecipient(code) ? ack.withInvalidRecipient() : ack;
    }

    private ProviderHttpResponse post(String path, String document) {
        ProviderHttpResponse response = ProviderRestClients.send(
                client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(document));
        if (response.isServerError()) {
            throw ProviderCallException.retryable(
                    String.valueOf(response.status()), Masking.body(response.body(), LOGGED_BODY_LENGTH));
        }
        return response;
    }

    private static ProviderCallException unreadable(ProviderHttpResponse response) {
        return ProviderCallException.transport(
                "SMS Gate answered %d without a status code: %s"
                        .formatted(response.status(), Masking.body(response.body(), LOGGED_BODY_LENGTH)),
                null);
    }

    private Optional<ProviderAck> throttleVerdict(SmsSubmission submission) {
        Optional<String> refusal = throttle.acquire(
                properties.providerCode(),
                runtimeSettings.rateLimitOf(properties.providerCode(), properties.rateLimit()),
                submission.recipient().value());
        if (refusal.isEmpty()) {
            return Optional.empty();
        }
        LOG.info("SMS Gate send held back for {}: {}", Masking.msisdn(submission.recipient()), refusal.get());
        return Optional.of(ProviderAck.failed(THROTTLED_CODE, ErrorClass.RETRYABLE, refusal.get(), clock.now()));
    }

    /**
     * Sending settings with the {@code provider.endpoint_config} of this provider applied (AD-07).
     *
     * <p>Resolved per call so that a changed sender name or weight applies within the configuration
     * refresh window rather than at the next restart (NF-07).
     */
    private SmsGateProperties.Sending sending() {
        return properties.sending().overlay(runtimeSettings.endpointConfigOf(properties.providerCode()));
    }

    /** {@code login} and {@code key}, resolved per call so a rotation applies without a restart (SG-04). */
    private SmsGateCredentials credentials() {
        SmsGateProperties.Credentials configured = properties.credentials();
        if (!configured.isConfigured()) {
            throw ProviderCallException.blocking(
                    "NO_CREDENTIALS", "no credential references are configured for SMS Gate (SG-04)");
        }
        return new SmsGateCredentials(secrets.require(configured.loginRef()), secrets.require(configured.keyRef()));
    }

    /**
     * Puts the answers of the sent messages back into the holes the throttled ones left.
     *
     * <p>Positional answers are the contract of the port, so a message the Hub held back must occupy
     * its own slot rather than shift its neighbours. A short answer from the provider — fewer elements
     * than were sent — repeats the last one, which is the conservative reading: the caller learns the
     * verdict rather than an accepted-by-default.
     */
    private static List<ProviderAck> merge(List<ProviderAck> withHoles, List<ProviderAck> sent) {
        List<ProviderAck> merged = new ArrayList<>(withHoles.size());
        int index = 0;
        for (ProviderAck held : withHoles) {
            if (held != null) {
                merged.add(held);
                continue;
            }
            merged.add(index < sent.size() ? sent.get(index) : sent.getLast());
            index++;
        }
        return List.copyOf(merged);
    }
}
