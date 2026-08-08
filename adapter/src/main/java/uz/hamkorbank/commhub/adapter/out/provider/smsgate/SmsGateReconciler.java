package uz.hamkorbank.commhub.adapter.out.provider.smsgate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uz.hamkorbank.commhub.adapter.out.provider.smsgate.SmsGateSendCodec.SmsGateItem;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallException;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderHttpResponse;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRestClients;
import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.port.in.ProcessProviderStatus;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Asks SMS Gate about messages whose delivery report never arrived (SG-03, §9.2).
 *
 * <p>FEEDBACK is a webhook, and a webhook is a report the Bank only receives if nothing between the two
 * networks dropped it. Without this job a lost report leaves a delivered message sitting at
 * {@code SENT_TO_PROVIDER} until its TTL turns it into {@code EXPIRED} — a delivered SMS reported to the
 * source system as expired. The job asks {@code /api/v2/search} by number and date for anything the
 * provider accepted more than {@link SmsGateProperties.Reconciliation#after()} ago and applies whatever
 * it finds.
 *
 * <p>It reaches the message through {@code ProcessProviderStatus}, the same use case the webhook uses,
 * which is what makes running both safe: a status already recorded is answered "ignored" and changes
 * nothing (AD-06). A report arriving twice, from two directions, is the normal case here.
 *
 * <p>It is a driving component living in a driven adapter's package, like the callback translator next
 * to it. Both are the same integration read in the other direction, and the alternative — a scheduler in
 * {@code adapter/in} that knows about SMS Gate — would put provider knowledge outside the provider's
 * package for no gain.
 *
 * <p>Off by default. Polling a provider on a timer costs them requests, so it is switched on for the
 * contours where FEEDBACK has proven lossy.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.smsgate.reconciliation", name = "enabled", havingValue = "true")
public class SmsGateReconciler {

    private static final Logger LOG = LoggerFactory.getLogger(SmsGateReconciler.class);

    private static final int LOGGED_BODY_LENGTH = 200;

    private final SmsGateProperties properties;
    private final SmsGateSendCodec codec;
    private final MessageRepository messages;
    private final ProcessProviderStatus processStatus;
    private final SecretResolverPort secrets;
    private final ClockPort clock;
    private final RestClient client;

    public SmsGateReconciler(
            SmsGateProperties properties,
            SmsGateSendCodec codec,
            MessageRepository messages,
            ProcessProviderStatus processStatus,
            SecretResolverPort secrets,
            ClockPort clock,
            ProviderRestClients clients) {
        this.properties = Guard.notNull(properties, "properties");
        this.codec = Guard.notNull(codec, "codec");
        this.messages = Guard.notNull(messages, "messages");
        this.processStatus = Guard.notNull(processStatus, "processStatus");
        this.secrets = Guard.notNull(secrets, "secrets");
        this.clock = Guard.notNull(clock, "clock");
        this.client = Guard.notNull(clients, "clients").create(properties.http());
    }

    /**
     * One pass over the overdue messages.
     *
     * <p>Failures of a single lookup are logged and skipped rather than propagated: the next message may
     * well be answerable, and the pass repeats on its own schedule anyway.
     */
    @Scheduled(
            fixedDelayString = "${commhub.provider.smsgate.reconciliation.interval:PT5M}",
            initialDelayString = "${commhub.provider.smsgate.reconciliation.interval:PT5M}")
    public void reconcile() {
        SmsGateProperties.Reconciliation settings = properties.reconciliation();
        Instant acceptedBefore = clock.now().minus(settings.after());
        List<Message> overdue =
                messages.findAwaitingDeliveryReport(providerCode(), acceptedBefore, settings.batchSize());
        if (overdue.isEmpty()) {
            return;
        }
        LOG.info("SMS Gate reconciliation: {} messages without a delivery report (SG-03)", overdue.size());
        int applied = 0;
        for (Message message : overdue) {
            try {
                applied += reconcileOne(message) ? 1 : 0;
            } catch (ProviderCallException e) {
                LOG.warn("SMS Gate reconciliation of {} failed: {}", message.id(), e.getMessage());
            }
        }
        LOG.info("SMS Gate reconciliation applied {} of {} statuses", applied, overdue.size());
    }

    private boolean reconcileOne(Message message) {
        DeliveryAttempt attempt = acceptedAttempt(message).orElse(null);
        Msisdn recipient = message.recipient().msisdn();
        if (attempt == null || recipient == null) {
            return false;
        }
        ProviderMessageId providerMessageId = attempt.providerMessageId().orElse(null);
        if (providerMessageId == null) {
            return false;
        }
        Instant sentAt = attempt.responseAt().orElse(attempt.requestAt());
        Optional<String> code = lookup(recipient, sentAt, providerMessageId);
        if (code.isEmpty()) {
            return false;
        }
        Optional<MessageStatus> canonical = SmsGateStatusCatalog.canonical(code.get());
        if (canonical.isEmpty()) {
            // Still Unknown at the provider (§18.2 code 6): ask again on the next pass.
            return false;
        }
        ProcessProviderStatusResult result = processStatus.process(new ProviderStatusCommand(
                providerCode(),
                providerMessageId,
                message.id(),
                canonical.get(),
                SmsGateStatusCatalog.describe(code.get()),
                "recovered by reconciliation (SG-03)",
                clock.now()));
        return result.applied();
    }

    /** The attempt whose acceptance the provider knows this message by (§18.2). */
    private static Optional<DeliveryAttempt> acceptedAttempt(Message message) {
        return message.attempts().stream()
                .filter(attempt -> attempt.result() == AttemptResult.ACCEPTED)
                .reduce((first, second) -> second);
    }

    /** {@code POST /api/v2/search} by number and date, returning the code of the matching entry (§9.2). */
    private Optional<String> lookup(Msisdn recipient, Instant sentAt, ProviderMessageId providerMessageId) {
        String document = codec.encodeSearch(credentials(), recipient.value(), sentAt.getEpochSecond());
        ProviderHttpResponse response = ProviderRestClients.send(client.post()
                .uri(SmsGateProperties.SEARCH_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(document));
        if (!response.isSuccess()) {
            LOG.warn(
                    "SMS Gate /api/v2/search for {} answered {}: {}",
                    Masking.msisdn(recipient),
                    response.status(),
                    Masking.body(response.body(), LOGGED_BODY_LENGTH));
            return Optional.empty();
        }
        return codec.readSearch(response.body()).stream()
                .filter(entry -> providerMessageId.value().equals(entry.id()))
                .map(SmsGateItem::code)
                .filter(code -> code != null && !code.isBlank())
                .findFirst();
    }

    private ProviderCode providerCode() {
        return ProviderCode.of(properties.providerCode());
    }

    private SmsGateCredentials credentials() {
        SmsGateProperties.Credentials configured = properties.credentials();
        if (!configured.isConfigured()) {
            throw ProviderCallException.blocking(
                    "NO_CREDENTIALS", "no credential references are configured for SMS Gate (SG-04)");
        }
        return new SmsGateCredentials(secrets.require(configured.loginRef()), secrets.require(configured.keyRef()));
    }
}
