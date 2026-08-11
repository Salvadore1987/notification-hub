package uz.hamkorbank.commhub.adapter.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.dto.ExpireMessagesResult;
import uz.hamkorbank.commhub.application.port.in.ExpireMessages;
import uz.hamkorbank.commhub.application.port.in.command.ExpireMessagesCommand;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Retires messages whose TTL or send window elapsed while they waited (FR-3.4).
 *
 * <p>The other half of the debt the dispatcher closes: a Hub that never expired anything reported a
 * message as still in flight days after the source system had given up on it.
 *
 * <p>Runs rarely on purpose. Expiry is a correction, not a deadline — the sending saga checks the TTL on
 * every turn of its own, so this sweep only catches what no turn came to.
 */
@Component
@ConditionalOnProperty(
        prefix = "commhub.dispatch.expiry",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ExpireMessagesScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ExpireMessagesScheduler.class);

    private final ExpireMessages expireMessages;
    private final ExpireMessagesProperties properties;

    public ExpireMessagesScheduler(ExpireMessages expireMessages, ExpireMessagesProperties properties) {
        this.expireMessages = Guard.notNull(expireMessages, "expireMessages");
        this.properties = Guard.notNull(properties, "properties");
    }

    @Scheduled(
            fixedDelayString = "${commhub.dispatch.expiry.interval:30s}",
            initialDelayString = "${commhub.dispatch.expiry.interval:30s}")
    public void expire() {
        ExpireMessagesCommand command = new ExpireMessagesCommand(properties.limit());
        for (int pass = 0; pass < properties.maxPassesPerTick(); pass++) {
            ExpireMessagesResult result = runPass(command);
            if (!result.more()) {
                return;
            }
        }
    }

    private ExpireMessagesResult runPass(ExpireMessagesCommand command) {
        try {
            return expireMessages.expire(command);
        } catch (RuntimeException e) {
            // Пропущенный тик безопаснее остановленного планировщика: сообщения никуда не денутся.
            LOG.error("TTL sweep failed; expired messages stay in flight until the next tick (FR-3.4)", e);
            return ExpireMessagesResult.none();
        }
    }
}
