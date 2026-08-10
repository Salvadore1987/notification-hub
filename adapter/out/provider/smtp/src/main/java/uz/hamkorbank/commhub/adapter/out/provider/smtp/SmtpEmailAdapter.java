package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Date;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderCallExecutor;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderRuntimeSettings;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderSupport;
import uz.hamkorbank.commhub.adapter.out.provider.support.ProviderThrottle;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.SecretResolverPort;
import uz.hamkorbank.commhub.application.port.out.provider.EmailProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.EmailSubmission;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * The corporate SMTP integration: the email provider of the Hub (§9.3, EM-01, EM-03).
 *
 * <p>The same shape as the SMS adapters, and the same contract with {@code ProviderCallExecutor}: a relay
 * that answered — even to refuse — produces a {@link ProviderAck}, and only a call that produced no verdict
 * throws (PR-01). That split matters more here than for SMS, because a mailing list full of retired mailboxes
 * generates a steady stream of {@code 550}s that says nothing whatsoever about the relay's health.
 *
 * <p>Three things distinguish it from an HTTP provider:
 *
 * <ul>
 *   <li><b>The connection is the expensive part.</b> Connections are pooled and their number is the
 *       concurrency limit of the channel ({@link SmtpTransportPool}); a virtual thread that finds none free
 *       waits and then fails the message over rather than opening a ninth connection (EM-01, AR-07).
 *   <li><b>The Hub assigns the identifier.</b> The relay's queue id is local to the relay and never comes
 *       back in a report, whereas the {@code Message-ID} written by the Hub is quoted by every DSN — so that
 *       is what the ack carries and what a bounce is matched by (EM-02).
 *   <li><b>Delivery is not confirmed by the send.</b> A {@code 250} means the relay took the message, which
 *       is exactly {@code SENT_TO_PROVIDER}; whether it arrived is answered later by a bounce or by silence.
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.smtp", name = "enabled", havingValue = "true")
public class SmtpEmailAdapter implements EmailProviderPort, AutoCloseable {

    /** Value of {@code provider.adapter_type} this bean serves (§10.1, AR-04). */
    public static final String ADAPTER_TYPE = "smtp";

    public static final String THROTTLED_CODE = "THROTTLED";

    /** The relay took the message; there is no finer answer at this point in an SMTP exchange. */
    public static final String ACCEPTED_CODE = "250";

    private static final int MAX_LOGGED_ERROR_LENGTH = 200;

    private static final Logger LOG = LoggerFactory.getLogger(SmtpEmailAdapter.class);

    private final SmtpProperties properties;
    private final SmtpMessageCodec codec;
    private final SmtpTransportPool pool;
    private final DkimSigner dkim;
    private final ProviderCallExecutor executor;
    private final ProviderThrottle throttle;
    private final ProviderRuntimeSettings runtimeSettings;
    private final SecretResolverPort secrets;
    private final ClockPort clock;
    private final Session session;

    public SmtpEmailAdapter(SmtpProperties properties, SmtpMessageCodec codec, ProviderSupport support) {
        this.properties = Guard.notNull(properties, "properties");
        this.codec = Guard.notNull(codec, "codec");
        Guard.notNull(support, "support");
        Guard.isTrue(
                properties.server().hasHost(),
                "commhub.provider.smtp.server.host is required when the email adapter is enabled (EM-01)");
        this.executor = support.executor();
        this.throttle = support.throttle();
        this.runtimeSettings = support.runtimeSettings();
        this.secrets = support.secrets();
        this.clock = support.clock();
        this.pool = new SmtpTransportPool(properties);
        this.dkim = new DkimSigner(properties.dkim(), support.secrets(), support.clock());
        this.session = pool.session();
        if (properties.server().security() == SmtpProperties.Security.NONE) {
            LOG.warn(
                    "SMTP relay {}:{} is used without TLS: mail leaves the Hub in clear text (EM-01)",
                    properties.server().host(),
                    properties.server().port());
        }
    }

    @Override
    public AdapterType adapterType() {
        return AdapterType.of(ADAPTER_TYPE);
    }

    @Override
    public ProviderAck submit(EmailSubmission submission) {
        Guard.notNull(submission, "submission");
        Optional<ProviderAck> held = throttleVerdict(submission);
        if (held.isPresent()) {
            return held.get();
        }
        return executor.execute(properties.providerCode(), properties.resilience(), () -> send(submission));
    }

    /**
     * One SMTP transaction.
     *
     * <p>The message is built inside the call rather than before it so that a retry re-serialises it: a DKIM
     * signature carries the moment it was made, and a signature made a minute ago on a retry would be the
     * kind of detail that only fails at a strict verifier (EM-03).
     */
    private ProviderAck send(EmailSubmission submission) {
        SmtpProperties.Sending sending = sending();
        try (SmtpTransportPool.Lease lease = pool.borrow(credentials())) {
            try {
                MimeMessage message = codec.encode(session, submission, sending, Date.from(clock.now()));
                if (dkim.isEnabled()) {
                    dkim.sign(message);
                }
                lease.transport().sendMessage(message, message.getAllRecipients());
                LOG.debug(
                        "SMTP relay accepted a message for {} ({} bytes of content)",
                        Masking.email(submission.recipient()),
                        submission.content().payloadSizeBytes());
                return ProviderAck.accepted(
                        ProviderMessageId.of(SmtpMessageCodec.providerMessageIdOf(submission, sending)),
                        ACCEPTED_CODE,
                        clock.now());
            } catch (AttachmentStore.AttachmentNotAvailableException e) {
                // Ссылка на вложение не разрешается — и не разрешится: это отказ сообщению, не релею.
                return ProviderAck.rejected("ATTACHMENT_UNAVAILABLE", e.getMessage(), clock.now());
            } catch (MessagingException e) {
                if (SmtpResponseCatalog.isConnectionSuspect(e)) {
                    lease.markFailed();
                }
                return refused(e, submission);
            }
        }
    }

    /**
     * Turns a failed SMTP exchange into an answer (PR-01, §9.3).
     *
     * <p>A {@code 4xx}, an authentication refusal and anything without a reply code are thrown, so that retry
     * and the circuit breaker see them. A {@code 5xx} about this message comes back as a rejection, and when
     * the relay said the mailbox does not exist the ack also carries the invalidation that puts the address
     * on the suppression list (EM-02, FR-5.1).
     */
    private ProviderAck refused(MessagingException failure, EmailSubmission submission) {
        Optional<String> replyCode = SmtpResponseCatalog.replyCodeOf(failure);
        Optional<String> enhanced = SmtpResponseCatalog.enhancedStatusOf(failure);
        if (replyCode.isEmpty() || SmtpResponseCatalog.countsAsProviderFailure(replyCode.get())) {
            throw SmtpResponseCatalog.toCallException(failure);
        }
        String description = describe(failure);
        LOG.info(
                "SMTP relay refused a message for {}: {} {} — {}",
                Masking.email(submission.recipient()),
                replyCode.get(),
                enhanced.orElse(""),
                description);
        ProviderAck ack = ProviderAck.rejected(replyCode.get(), description, clock.now());
        return SmtpResponseCatalog.invalidatesRecipient(enhanced.orElse(null)) ? ack.withInvalidRecipient() : ack;
    }

    /**
     * Description of a refusal for the audit trail.
     *
     * <p>Masked and truncated before it is kept: a relay echoes the rejected address into its own error text
     * often enough that an SMTP error is a place personal data ends up by accident (PR-03, OBS-03).
     */
    private static String describe(MessagingException failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return Masking.body(message, MAX_LOGGED_ERROR_LENGTH);
    }

    private Optional<ProviderAck> throttleVerdict(EmailSubmission submission) {
        Optional<String> refusal = throttle.acquire(
                properties.providerCode(),
                runtimeSettings.rateLimitOf(properties.providerCode(), properties.rateLimit()),
                submission.recipient().value());
        if (refusal.isEmpty()) {
            return Optional.empty();
        }
        LOG.info("SMTP send held back for {}: {}", Masking.email(submission.recipient()), refusal.get());
        return Optional.of(ProviderAck.failed(THROTTLED_CODE, ErrorClass.RETRYABLE, refusal.get(), clock.now()));
    }

    /**
     * Sender settings with {@code provider.endpoint_config} applied (AD-07).
     *
     * <p>Resolved per call, so a changed sender or display name applies within the configuration refresh
     * window rather than at the next restart (NF-07).
     */
    private SmtpProperties.Sending sending() {
        return properties.sending().overlay(runtimeSettings.endpointConfigOf(properties.providerCode()));
    }

    /**
     * Relay credentials, resolved per call so a rotation applies without a restart (SEC-04).
     *
     * @return {@code null} for a relay that does not authenticate, which is normal inside the Bank's network
     */
    private SmtpCredentials credentials() {
        SmtpProperties.Credentials configured = properties.server().credentials();
        if (!configured.isConfigured()) {
            return null;
        }
        return new SmtpCredentials(
                secrets.require(configured.usernameRef()), secrets.require(configured.passwordRef()));
    }

    /** Closes the pooled connections on shutdown; the relay sees a QUIT rather than a dropped socket (NF-05). */
    @Override
    public void close() {
        pool.close();
    }

    /** The signer, so that a key rotation can be applied without restarting the adapter (SEC-04, EM-03). */
    public DkimSigner dkimSigner() {
        return dkim;
    }
}
