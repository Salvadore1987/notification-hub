package uz.hamkorbank.commhub.application.service.support;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.port.out.BatchRepository;
import uz.hamkorbank.commhub.application.port.out.KillSwitchPort;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.Batch;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.BatchStatus;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Everything that can stop a message on its way to a provider (FR-3.2, FR-3.4, FR-5.3, FR-8.5).
 *
 * <p>Checked on every turn of the sending saga, not once at acceptance, because all of it changes
 * while the message waits: an operator pauses the batch, suspends the stream or flips the kill
 * switch, the TTL elapses, the quiet-hours window opens.
 *
 * <p>Only a stopped batch cancels the message — that state is terminal for the batch (FR-3.2). A
 * pause, a suspended stream and the kill switch are reversible and therefore only defer it; OTP is
 * never held by the kill switch unless it was included explicitly (SRS §6.2).
 */
@Component
public class DispatchGuards {

    private final KillSwitchPort killSwitch;
    private final BatchRepository batches;
    private final StreamRepository streams;

    public DispatchGuards(KillSwitchPort killSwitch, BatchRepository batches, StreamRepository streams) {
        this.killSwitch = Guard.notNull(killSwitch, "killSwitch");
        this.batches = Guard.notNull(batches, "batches");
        this.streams = Guard.notNull(streams, "streams");
    }

    /** Decides what to do with the message at this instant. */
    public DispatchGate evaluate(Message message, Instant now) {
        Guard.notNull(message, "message");
        Guard.notNull(now, "now");
        if (message.isExpiredAt(now)) {
            return DispatchGate.expire("TTL or send window elapsed before the message reached a provider");
        }
        TrafficClass trafficClass = message.envelope().trafficClass();
        if (killSwitch.state().stops(trafficClass)) {
            return DispatchGate.defer("sending is stopped by the kill switch");
        }
        DispatchGate batchGate = checkBatch(message);
        if (!batchGate.isProceed()) {
            return batchGate;
        }
        DispatchGate streamGate = checkStream(message, trafficClass, now);
        if (!streamGate.isProceed()) {
            return streamGate;
        }
        if (!message.timing().isSendableAt(now)) {
            return DispatchGate.defer("send window is not open yet");
        }
        return DispatchGate.proceed();
    }

    /** A stopped batch cancels its remaining messages; a paused one only holds them (FR-3.2). */
    private DispatchGate checkBatch(Message message) {
        Optional<Batch> batch = message.envelope().batchIdOptional().flatMap(batches::findById);
        if (batch.isEmpty()) {
            return DispatchGate.proceed();
        }
        BatchStatus status = batch.get().status();
        if (status == BatchStatus.STOPPED) {
            return DispatchGate.cancel(RejectionReason.SEND_STOPPED, "batch was stopped by an operator");
        }
        if (!batch.get().isDispatchable()) {
            return DispatchGate.defer("batch is " + status);
        }
        return DispatchGate.proceed();
    }

    /**
     * Stream state and its quiet-hours window (FR-3.2, FR-5.3).
     *
     * <p>The window is re-evaluated here because a message accepted before it opened must still be
     * held once it does; the channel-level window was already applied at acceptance.
     */
    private DispatchGate checkStream(Message message, TrafficClass trafficClass, Instant now) {
        Optional<Stream> stream = streams.findById(message.envelope().streamId());
        if (stream.isEmpty()) {
            return DispatchGate.proceed();
        }
        if (!stream.get().isAcceptingTraffic() && trafficClass.stoppableByDefault()) {
            return DispatchGate.defer("stream is " + stream.get().status());
        }
        Optional<QuietHours> quietHours = stream.get().quietHours();
        if (trafficClass.respectsQuietHours()
                && quietHours.isPresent()
                && quietHours.get().isQuietAt(now)) {
            return quietHours.get().defersDelivery()
                    ? DispatchGate.deferUntil(
                            quietHours.get().nextOpeningAt(now),
                            "quiet hours until " + quietHours.get().nextOpeningAt(now))
                    : DispatchGate.cancel(RejectionReason.QUIET_HOURS, "quiet-hours window rejects the message");
        }
        return DispatchGate.proceed();
    }
}
