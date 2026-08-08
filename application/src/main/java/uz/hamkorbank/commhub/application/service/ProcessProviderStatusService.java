package uz.hamkorbank.commhub.application.service;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ProcessProviderStatusResult;
import uz.hamkorbank.commhub.application.port.in.ProcessProviderStatus;
import uz.hamkorbank.commhub.application.port.in.command.ProviderStatusCommand;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.model.type.MessageStatus;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Applies a provider delivery report to its message (AD-06, ST-01, ST-03, PM-02, SG-02).
 *
 * <p>Idempotent by construction: providers repeat their callbacks and the reconciliation job may
 * report the same status again (SG-03), so a report that would not change anything — same status, a
 * message that is already terminal, a transition the state machine forbids — is answered as "ignored"
 * and the adapter still confirms with 200. Only a genuine change is recorded, with its history entry
 * and its outbound event (ST-01, §6.4).
 */
@Service
public class ProcessProviderStatusService implements ProcessProviderStatus {

    private final MessageRepository messages;
    private final MessageStatusNotifier notifier;

    public ProcessProviderStatusService(MessageRepository messages, MessageStatusNotifier notifier) {
        this.messages = Guard.notNull(messages, "messages");
        this.notifier = Guard.notNull(notifier, "notifier");
    }

    @Override
    @Transactional
    public ProcessProviderStatusResult process(ProviderStatusCommand command) {
        Guard.notNull(command, "command");
        Optional<Message> found = locate(command);
        if (found.isEmpty()) {
            return ProcessProviderStatusResult.unknownMessage("no message for provider %s and id %s"
                    .formatted(command.providerCode(), command.providerMessageId()));
        }
        Message message = found.get();
        if (message.status() == command.status()) {
            return ProcessProviderStatusResult.ignored(message.id(), message.status(), "status already recorded");
        }
        if (!message.status().canTransitionTo(command.status())) {
            return ProcessProviderStatusResult.ignored(
                    message.id(),
                    message.status(),
                    "%s does not follow %s".formatted(command.status(), message.status()));
        }
        StatusChange change = apply(message, command);
        messages.save(message);
        notifier.publish(message, change);
        return ProcessProviderStatusResult.applied(message.id(), message.status());
    }

    /** The callback may carry the Hub id; otherwise the provider-side id identifies the message. */
    private Optional<Message> locate(ProviderStatusCommand command) {
        return command.messageIdOptional().flatMap(messages::findById).or(() -> command.providerMessageIdOptional()
                .flatMap(providerMessageId ->
                        messages.findByProviderMessageId(command.providerCode(), providerMessageId)));
    }

    /** Records the change with the provider as its actor and the raw status as its detail (ST-01). */
    private static StatusChange apply(Message message, ProviderStatusCommand command) {
        Actor actor = Actor.provider(command.providerCode().value());
        String detail = command.detail() == null ? command.providerStatus() : command.detail();
        if (command.status() == MessageStatus.DELIVERED) {
            return message.markDelivered(command.providerStatus(), actor, command.occurredAt());
        }
        if (command.status() == MessageStatus.UNDELIVERED) {
            return message.markUndelivered(command.providerStatus(), actor, command.occurredAt());
        }
        if (command.status() == MessageStatus.EXPIRED) {
            return message.expire(actor, command.occurredAt());
        }
        return message.transitionTo(command.status(), null, detail, actor, command.occurredAt());
    }
}
