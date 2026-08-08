package uz.hamkorbank.commhub.application.service.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.mapper.ProviderSubmissionMapper;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.PushDelivery;
import uz.hamkorbank.commhub.application.port.out.PushDeliveryLogPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.PushProviderPort;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.type.AttemptResult;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Delivers one notification to every active device of the recipient (PU-09, PU-10, §9.4).
 *
 * <p>Push is the only channel where one message has several addresses, and this is where that fact is
 * confined. The sending saga still sees a single provider call with a single answer — which is what
 * keeps it channel-agnostic (AR-05) — while underneath, one submission per token goes out and every
 * device gets its own row (PU-09).
 *
 * <p><b>Aggregation.</b> One accepted device makes the message accepted: the customer was notified, and
 * failing a message whose phone got it because the tablet's token had expired would be a lie to the
 * source system. When no device was accepted the worst class wins — blocking over retryable over
 * permanent — because that is the one the saga has to act on: an authentication failure must fail the
 * provider over even if the other token merely had a bad payload.
 *
 * <p><b>Retired devices are skipped before the call.</b> A token the platform has already declared dead
 * stays in the source system's submissions for as long as it takes them to consume the invalidation
 * event, and a bulk campaign would otherwise pay one HTTP call per message for each of them (PU-04).
 *
 * <p><b>Parallel over virtual threads</b> when there is more than one device (AR-07, PU-10): the calls
 * are independent, blocking and network-bound, so the fan-out of a four-device recipient costs the
 * latency of its slowest device rather than the sum. A single-device recipient — the overwhelming
 * majority — stays on the calling thread and pays nothing for the machinery.
 *
 * <p>Only the provider calls run off-thread. The suppression writes, the outbox events and the device
 * rows all happen on the caller's thread, inside the caller's transaction, after the joins: a
 * transaction is bound to a thread, and a token retired on a worker thread would be committed by
 * nobody.
 *
 * <p>Nothing here logs — this layer has no logging dependency by design — and nothing here needs to:
 * what each device answered is written as a row rather than a line, which is also the only form an
 * operator can filter and count (PU-09, OBS-01).
 */
@Component
public class PushFanOut {

    /** Response code of an answer produced by the Hub itself rather than by a platform. */
    public static final String NO_DEVICE_CODE = "NO_ACTIVE_DEVICE";

    public static final String NO_ADAPTER_CODE = "NO_PUSH_ADAPTER";

    private final ObjectProvider<PushProviderPort> adapters;
    private final ProviderSubmissionMapper mapper;
    private final PushTokenRegistrar tokens;
    private final PushDeliveryLogPort deliveries;
    private final ClockPort clock;

    public PushFanOut(
            ObjectProvider<PushProviderPort> adapters,
            ProviderSubmissionMapper mapper,
            PushTokenRegistrar tokens,
            PushDeliveryLogPort deliveries,
            ClockPort clock) {
        this.adapters = Guard.notNull(adapters, "adapters");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.tokens = Guard.notNull(tokens, "tokens");
        this.deliveries = Guard.notNull(deliveries, "deliveries");
        this.clock = Guard.notNull(clock, "clock");
    }

    /** Whether a push adapter is deployed for the routed provider. */
    public boolean supports(ProviderRef provider) {
        return adapter(provider).isPresent();
    }

    /**
     * Sends the notification to every device the routed adapter serves.
     *
     * @param attempt attempt of the saga this fan-out belongs to; every device row points at it
     * @return the aggregated answer, which is what decides the status of the message (PU-09)
     */
    public ProviderAck submit(Message message, ProviderRef provider, DeliveryAttempt attempt) {
        Guard.notNull(message, "message");
        Guard.notNull(provider, "provider");
        Guard.notNull(attempt, "attempt");
        Optional<PushProviderPort> adapter = adapter(provider);
        if (adapter.isEmpty()) {
            return ProviderAck.failed(
                    NO_ADAPTER_CODE,
                    ErrorClass.NON_RETRYABLE,
                    "no push adapter is deployed for provider "
                            + provider.code().value(),
                    clock.now());
        }
        List<PushToken> devices = devicesFor(adapter.get(), message);
        if (devices.isEmpty()) {
            return noDevice(message, provider, adapter.get());
        }
        List<TokenAck> answers = call(adapter.get(), message, provider, devices);
        applyInvalidations(message, provider, attempt, answers);
        return aggregate(provider, answers);
    }

    /**
     * Devices this adapter can address, minus the ones already retired (PU-04, §9.4).
     *
     * <p>A token whose platform the adapter does not serve is not an error here: the route named one
     * provider, and an iOS device on an Android route is a question for the channel's configuration —
     * either the FCM-for-both mode of PU-05 or a routing policy per platform. It is reported in the
     * answer's detail rather than silently dropped.
     */
    private List<PushToken> devicesFor(PushProviderPort adapter, Message message) {
        Instant now = clock.now();
        List<PushToken> devices = new ArrayList<>();
        for (PushToken token : message.recipient().pushTokens()) {
            if (!adapter.supportsPlatform(token.platform())) {
                continue;
            }
            if (tokens.isRetired(token, now)) {
                continue;
            }
            devices.add(token);
        }
        return List.copyOf(devices);
    }

    /** One call per device, in parallel when there is more than one (PU-10, AR-07). */
    private List<TokenAck> call(
            PushProviderPort adapter, Message message, ProviderRef provider, List<PushToken> devices) {
        if (devices.size() == 1) {
            PushToken only = devices.getFirst();
            return List.of(new TokenAck(only, submitOne(adapter, message, provider, only)));
        }
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TokenAck>> futures = devices.stream()
                    .map(token ->
                            workers.submit(() -> new TokenAck(token, submitOne(adapter, message, provider, token))))
                    .toList();
            return futures.stream().map(PushFanOut::join).toList();
        }
    }

    /**
     * One device.
     *
     * <p>An adapter that throws would otherwise take the whole fan-out with it, so a failure here
     * becomes this device's retryable answer and the others are still delivered — the same contract the
     * gateway gives the saga (PR-01).
     */
    private ProviderAck submitOne(PushProviderPort adapter, Message message, ProviderRef provider, PushToken token) {
        try {
            return adapter.submit(mapper.toPushSubmission(message, provider, token));
        } catch (RuntimeException e) {
            return ProviderAck.failed(
                    "ADAPTER_ERROR",
                    ErrorClass.RETRYABLE,
                    "%s: %s".formatted(e.getClass().getSimpleName(), e.getMessage()),
                    clock.now());
        }
    }

    private static TokenAck join(Future<TokenAck> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("push fan-out was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("push fan-out failed", e.getCause());
        }
    }

    /** Retires the devices the platform refused and records what every device answered (PU-04, PU-09). */
    private void applyInvalidations(
            Message message, ProviderRef provider, DeliveryAttempt attempt, List<TokenAck> answers) {
        List<PushDelivery> rows = new ArrayList<>(answers.size());
        for (TokenAck answer : answers) {
            boolean retired = answer.ack().invalidRecipient()
                    && tokens.invalidate(
                            message,
                            provider,
                            answer.token(),
                            reasonOf(answer.ack()),
                            answer.ack().respondedAt());
            rows.add(rowOf(message, provider, attempt, answer, retired));
        }
        deliveries.record(rows);
    }

    private static PushDelivery rowOf(
            Message message, ProviderRef provider, DeliveryAttempt attempt, TokenAck answer, boolean retired) {
        ProviderAck ack = answer.ack();
        return new PushDelivery(
                message.id(),
                attempt.id(),
                provider,
                RecipientAddresses.of(answer.token()),
                answer.token().platform(),
                ack.providerMessageId(),
                new PushDelivery.Outcome(
                        ack.result(), ack.responseCode(), ack.errorDescription(), retired, ack.respondedAt()));
    }

    /**
     * The recipient has no device this provider can write to.
     *
     * <p>Permanent rather than retryable: retrying changes nothing, and failing over would hand the
     * message to a provider of the same channel with the same empty device list. The message ends as
     * {@code UNDELIVERED} with a reason an operator can act on.
     */
    private ProviderAck noDevice(Message message, ProviderRef provider, PushProviderPort adapter) {
        return ProviderAck.rejected(
                NO_DEVICE_CODE,
                "recipient has no active device token served by %s (%s registered, %s served platforms)"
                        .formatted(
                                provider.code().value(),
                                message.recipient().pushTokens().size(),
                                servedPlatforms(adapter)),
                clock.now());
    }

    /**
     * The answer the saga sees (PU-09).
     *
     * <p>The provider message id of the first accepted device travels with it: the aggregate is a
     * statement about the message, and the message reached the platform under that id.
     */
    private ProviderAck aggregate(ProviderRef provider, List<TokenAck> answers) {
        Optional<TokenAck> accepted =
                answers.stream().filter(answer -> answer.ack().isAccepted()).findFirst();
        Instant respondedAt = answers.getLast().ack().respondedAt();
        if (accepted.isPresent()) {
            ProviderAck first = accepted.get().ack();
            return ProviderAck.accepted(first.providerMessageId(), first.responseCode(), respondedAt);
        }
        ProviderAck worst = answers.stream()
                .map(TokenAck::ack)
                .max(Comparator.comparingInt(PushFanOut::severity))
                .orElseThrow();
        String detail = describe(answers);
        if (worst.result() == AttemptResult.REJECTED) {
            return ProviderAck.rejected(worst.responseCode(), detail, respondedAt);
        }
        return ProviderAck.failed(worst.responseCode(), worst.errorClass(), detail, respondedAt);
    }

    /** Blocking beats retryable beats permanent: the strongest instruction to the saga wins. */
    private static int severity(ProviderAck ack) {
        return switch (ack.errorClass()) {
            case BLOCKING -> 3;
            case RETRYABLE -> 2;
            case NON_RETRYABLE -> 1;
        };
    }

    /** Per-device detail for the attempt's error description; tokens appear masked only (OBS-03). */
    private static String describe(List<TokenAck> answers) {
        return answers.stream()
                .map(answer -> "%s=%s".formatted(answer.token().masked(), reasonOf(answer.ack())))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private static String reasonOf(ProviderAck ack) {
        if (ack.errorDescription() != null && !ack.errorDescription().isBlank()) {
            return ack.errorDescription();
        }
        return ack.responseCode() == null ? ack.result().name() : ack.responseCode();
    }

    private static String servedPlatforms(PushProviderPort adapter) {
        return Arrays.stream(PushPlatform.values())
                .filter(adapter::supportsPlatform)
                .map(Enum::name)
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    private Optional<PushProviderPort> adapter(ProviderRef provider) {
        return provider == null
                ? Optional.empty()
                : adapters.stream().filter(port -> port.supports(provider)).findFirst();
    }

    /** What one device answered. */
    private record TokenAck(PushToken token, ProviderAck ack) {}
}
