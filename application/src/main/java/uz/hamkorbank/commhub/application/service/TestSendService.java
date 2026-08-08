package uz.hamkorbank.commhub.application.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.SubmitMessageResult;
import uz.hamkorbank.commhub.application.exception.NotFoundException;
import uz.hamkorbank.commhub.application.port.in.SendTestMessage;
import uz.hamkorbank.commhub.application.port.in.SubmitMessage;
import uz.hamkorbank.commhub.application.port.in.command.SendTestMessageCommand;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.application.port.out.AuditEntry;
import uz.hamkorbank.commhub.application.port.out.AuditPort;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.domain.model.ChannelPlan;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.content.EmailContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContent;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.vo.DedupKey;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;
import uz.hamkorbank.commhub.domain.support.UuidV7;

/**
 * The configuration test send of FR-7.4.
 *
 * <p>It delegates to {@link SubmitMessage} instead of doing anything itself, and that is the whole design:
 * what an operator wants to know before enabling a provider is whether <em>the pipeline</em> will deliver
 * through it — with this stream's quotas, this channel's filters, this provider's credentials and this
 * platform's sandbox rules. A separate sending path would answer a different question.
 *
 * <p>Three things are set on the submission and nothing else is special:
 *
 * <ul>
 *   <li>the TEST flag, which keeps the send out of the business figures (FR-7.4) and puts APNs into its
 *       sandbox and FCM into {@code validate_only} (PU-13);
 *   <li>a fresh dedup key, so that sending the same text to the same number twice in a row actually sends
 *       twice — an operator repeating a test is not a duplicate submission (FR-1.5);
 *   <li>the pinned provider, when one was named, so the profile under test is the one that runs.
 * </ul>
 *
 * <p>The audit entry is written before the send and not after it: what has to be journalled is that a
 * person directed the Bank's infrastructure at a live address, and that is true whether or not the send
 * then succeeded (FR-7.3, SEC-03).
 */
@Service
public class TestSendService implements SendTestMessage {

    /** Prefix of the external id, so a test send is recognisable in the message list without a join. */
    private static final String EXTERNAL_ID_PREFIX = "test-";

    private static final String AUDIT_ACTION = "message.test-send";

    private static final String AUDIT_ENTITY = "channel";

    private final SubmitMessage submitMessage;
    private final ProviderConfigRepository providerConfig;
    private final AuditPort audit;
    private final ClockPort clock;

    public TestSendService(
            SubmitMessage submitMessage, ProviderConfigRepository providerConfig, AuditPort audit, ClockPort clock) {
        this.submitMessage = Guard.notNull(submitMessage, "submitMessage");
        this.providerConfig = Guard.notNull(providerConfig, "providerConfig");
        this.audit = Guard.notNull(audit, "audit");
        this.clock = Guard.notNull(clock, "clock");
    }

    @Override
    @Transactional
    public SubmitMessageResult send(SendTestMessageCommand command) {
        Guard.notNull(command, "command");
        ProviderRef pinned = command.providerOptional()
                .map(code -> providerConfig
                        .findProviderByCode(code)
                        .orElseThrow(() -> NotFoundException.of("provider", code.value())))
                .map(Provider::ref)
                .orElse(null);
        if (pinned != null && pinned.channel() != command.channel()) {
            throw new IllegalArgumentException(
                    "provider %s serves %s, not %s".formatted(command.provider(), pinned.channel(), command.channel()));
        }
        audit.write(AuditEntry.of(
                command.actor(), AUDIT_ACTION, AUDIT_ENTITY, command.channel().name(), clock.now()));
        return submitMessage.submit(submissionOf(command, pinned));
    }

    private static SubmitMessageCommand submissionOf(SendTestMessageCommand command, ProviderRef pinned) {
        String externalId = EXTERNAL_ID_PREFIX + UuidV7.generate();
        return new SubmitMessageCommand(
                command.streamId(),
                ExternalMessageId.of(externalId),
                null,
                command.recipient(),
                MessageContents.of(contentOf(command)),
                ChannelPlan.explicitChannel(command.channel()),
                null,
                SubmitMessageCommand.Delivery.testSend(null, DedupKey.of(externalId), pinned));
    }

    /**
     * The operator's text as content of the channel under test.
     *
     * <p>No template: a test send checks the transport, and a template check is what the preview of
     * FR-4.4 is for. The push title is fixed for the same reason — what is being verified is that the
     * device receives something, not what it says.
     */
    private static MessageContent contentOf(SendTestMessageCommand command) {
        return switch (command.channel()) {
            case SMS -> SmsContent.of(command.text());
            case EMAIL ->
                EmailContent.ofText(command.subjectOptional().orElse("Notification Hub test"), command.text());
            case PUSH -> PushContent.of("Notification Hub", command.text());
        };
    }
}
