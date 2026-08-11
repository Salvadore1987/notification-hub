package uz.hamkorbank.commhub.adapter.out.provider.smtp;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.adapter.out.provider.support.Masking;
import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.port.in.ProcessProviderStatus;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reads non-delivery reports out of the bounce mailbox and applies them (EM-02, §9.3).
 *
 * <p>Email has no callback: the answer to "did it arrive" comes back as another email, to the address the
 * envelope named as its sender. This is the poller for that mailbox, and it is the email counterpart of the
 * SMS Gate reconciliation next door — a driving component living in the provider's own package, because what
 * it knows is the provider's status vocabulary (AR-04).
 *
 * <p>It goes through {@code ProcessProviderStatus}, the same use case every DLR goes through (AD-06). That
 * is what makes a repeated report harmless: a status already recorded is answered "ignored" and changes
 * nothing, and a mailbox is re-read after a crash by definition.
 *
 * <p>A hard bounce carries a second consequence — the address itself stops being usable (FR-5.1) — and it
 * travels on the same command as {@code suppressAs}. The two are applied together and independently: a report
 * that changes no status, because the message is already terminal, must still stop the next send to that
 * address. That decision was made in Phase 10 and this is the path it was made for.
 *
 * <p>Messages are marked as seen rather than deleted by default: a report the Hub could not attribute is
 * evidence, and evidence a poller silently deleted cannot be looked at afterwards.
 */
@Component
@ConditionalOnProperty(prefix = "commhub.provider.smtp.bounce", name = "enabled", havingValue = "true")
public class EmailBouncePoller {

    private static final Logger LOG = LoggerFactory.getLogger(EmailBouncePoller.class);

    private final SmtpBounceProperties properties;
    private final SmtpProperties smtpProperties;
    private final BounceCodec codec;
    private final ProcessProviderStatus processStatus;
    private final ClockPort clock;

    public EmailBouncePoller(
            SmtpBounceProperties properties,
            SmtpProperties smtpProperties,
            BounceCodec codec,
            ProcessProviderStatus processStatus,
            ClockPort clock) {
        this.properties = Guard.notNull(properties, "properties");
        this.smtpProperties = Guard.notNull(smtpProperties, "smtpProperties");
        this.codec = Guard.notNull(codec, "codec");
        this.processStatus = Guard.notNull(processStatus, "processStatus");
        this.clock = Guard.notNull(clock, "clock");
    }

    /**
     * One pass over the unread reports.
     *
     * <p>A failure of the pass is logged and swallowed: the mailbox is still there and the next pass will
     * read it, whereas an exception out of a scheduled method only stops the schedule.
     */
    @Scheduled(
            fixedDelayString = "${commhub.provider.smtp.bounce.settings.interval:PT5M}",
            initialDelayString = "${commhub.provider.smtp.bounce.settings.interval:PT5M}")
    public void poll() {
        try {
            int applied = readMailbox();
            if (applied > 0) {
                LOG.info("Applied {} non-delivery reports from {} (EM-02)", applied, properties.describe());
            }
        } catch (MessagingException | IOException | RuntimeException e) {
            LOG.warn("Reading the bounce mailbox {} failed: {}", properties.describe(), e.getMessage());
        }
    }

    private int readMailbox() throws MessagingException, IOException {
        Session session = Session.getInstance(properties.sessionProperties());
        try (Store store = session.getStore(properties.protocol())) {
            connect(store);
            Folder folder = store.getFolder(properties.folder());
            folder.open(Folder.READ_WRITE);
            try {
                return process(folder);
            } finally {
                // expunge только когда мы действительно удаляем: иначе закрытие не должно ничего чистить.
                folder.close(properties.settings().deleteProcessed());
            }
        }
    }

    private void connect(Store store) throws MessagingException {
        SmtpBounceProperties.Credentials credentials = properties.credentials();
        if (credentials.isConfigured()) {
            store.connect(properties.host(), properties.port(), credentials.username(), credentials.password());
        } else {
            store.connect(properties.host(), properties.port(), null, null);
        }
    }

    private int process(Folder folder) throws MessagingException, IOException {
        Message[] unread = folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
        int applied = 0;
        int inspected = 0;
        for (Message message : unread) {
            if (inspected++ >= properties.batchSize()) {
                LOG.info(
                        "Bounce mailbox {} has more than {} unread reports; the rest waits for the next pass",
                        properties.describe(),
                        properties.batchSize());
                break;
            }
            applied += apply(message) ? 1 : 0;
            mark(message);
        }
        return applied;
    }

    /**
     * Applies one report.
     *
     * @return whether it changed anything; an unattributable or non-final report changes nothing
     */
    private boolean apply(Message message) throws MessagingException, IOException {
        Optional<BounceReport> parsed = codec.parse(message);
        if (parsed.isEmpty()) {
            LOG.debug("A message in {} is not a non-delivery report the Hub can read", properties.describe());
            return false;
        }
        BounceReport report = parsed.get();
        if (!report.isAttributable()) {
            LOG.warn(
                    "A non-delivery report for {} names no message the Hub sent (status {}) — left in {}",
                    Masking.email(report.recipient()),
                    report.statusOptional().orElse("-"),
                    properties.describe());
            return false;
        }
        Optional<MessageStatus> status = BounceCatalog.statusOf(report);
        if (status.isEmpty()) {
            // Задержка — не исход: релей ещё пробует, и переход ST-01 для «пока не знаю» не существует.
            LOG.debug("Non-delivery report for {} is a delay, applied as nothing", report.hubMessageId());
            return false;
        }
        ProcessProviderStatusResult result = processStatus.process(command(report, status.get()));
        if (BounceCatalog.isHardBounce(report)) {
            LOG.info(
                    "Hard bounce for {}: {} — the address is suppressed (EM-02)",
                    Masking.email(report.recipient()),
                    report.statusOptional().orElse("-"));
        }
        return result.applied();
    }

    private ProviderStatusCommand command(BounceReport report, MessageStatus status) {
        SuppressionReason suppression = BounceCatalog.suppressionOf(report).orElse(null);
        return new ProviderStatusCommand(
                ProviderCode.of(smtpProperties.providerCode()),
                null,
                report.hubMessageId(),
                status,
                report.statusOptional().orElse(report.action()),
                report.diagnosticCode(),
                suppression,
                clock.now());
    }

    /** Marks a processed report so the next pass does not read it again. */
    private void mark(Message message) throws MessagingException {
        message.setFlag(Flags.Flag.SEEN, true);
        if (properties.settings().deleteProcessed()) {
            message.setFlag(Flags.Flag.DELETED, true);
        }
    }
}
