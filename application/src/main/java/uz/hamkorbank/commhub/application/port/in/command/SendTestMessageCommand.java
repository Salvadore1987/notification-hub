package uz.hamkorbank.commhub.application.port.in.command;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Test send that verifies a channel or a provider profile before it carries traffic (FR-7.4, §11.2).
 *
 * <p>An operator's command, not a source system's: it names the address to try, and optionally the
 * provider whose configuration is being checked. That is why the actor is a component here — the person
 * who sent a message to a live handset is exactly what the audit journal has to name (FR-7.3).
 *
 * @param streamId stream the test is sent as; its quotas, filters and templates apply, because a test
 *     that skips them tests nothing that production will do
 * @param provider provider to pin; {@code null} lets routing choose, which is how a <em>channel</em> is
 *     tested rather than a provider
 * @param text body to send; short and written by the operator, so no template is involved
 * @param subject subject line for the email channel; ignored elsewhere
 */
public record SendTestMessageCommand(
        Actor actor,
        StreamId streamId,
        Channel channel,
        Recipient recipient,
        ProviderCode provider,
        String text,
        String subject) {

    public static final int MAX_TEXT_LENGTH = 500;

    public SendTestMessageCommand {
        Guard.notNull(actor, "SendTestMessageCommand.actor");
        Guard.notNull(streamId, "SendTestMessageCommand.streamId");
        Guard.notNull(channel, "SendTestMessageCommand.channel");
        Guard.notNull(recipient, "SendTestMessageCommand.recipient");
        Guard.notBlank(text, "SendTestMessageCommand.text");
        Guard.maxLength(text, MAX_TEXT_LENGTH, "SendTestMessageCommand.text");
    }

    public Optional<ProviderCode> providerOptional() {
        return Optional.ofNullable(provider);
    }

    public Optional<String> subjectOptional() {
        return Optional.ofNullable(subject);
    }
}
