package uz.hamkorbank.commhub.application.service;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.ExpireMessagesResult;
import uz.hamkorbank.commhub.application.port.in.ExpireMessages;
import uz.hamkorbank.commhub.application.port.in.command.ExpireMessagesCommand;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.MessageRepository;
import uz.hamkorbank.commhub.application.service.support.MessageStatusNotifier;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.StatusChange;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Moves messages whose TTL or send window elapsed to {@code EXPIRED} (FR-3.4).
 *
 * <p>Critical for OTP: a code that outlived its validity must never be handed to a provider. The
 * sweep is bounded so its transaction stays short; {@link ExpireMessagesResult#more()} tells the
 * scheduler to run again immediately when the limit was hit.
 *
 * <p>The sending saga performs the same check before every provider call, so a message expiring
 * between two sweeps is caught as well.
 */
@Service
public class ExpireMessagesService implements ExpireMessages {

    private final ClockPort clock;
    private final MessageRepository messages;
    private final MessageStatusNotifier notifier;

    public ExpireMessagesService(ClockPort clock, MessageRepository messages, MessageStatusNotifier notifier) {
        this.clock = Guard.notNull(clock, "clock");
        this.messages = Guard.notNull(messages, "messages");
        this.notifier = Guard.notNull(notifier, "notifier");
    }

    @Override
    @Transactional
    public ExpireMessagesResult expire(ExpireMessagesCommand command) {
        Guard.notNull(command, "command");
        Instant now = clock.now();
        List<Message> expired = messages.findExpired(now, command.limit());
        int applied = 0;
        for (Message message : expired) {
            if (!message.isExpiredAt(now)) {
                continue;
            }
            StatusChange change = message.expire(Actor.system(), now);
            messages.save(message);
            notifier.publish(message, change);
            applied++;
        }
        return new ExpireMessagesResult(applied, expired.size() >= command.limit());
    }
}
