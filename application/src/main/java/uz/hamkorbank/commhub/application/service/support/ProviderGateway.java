package uz.hamkorbank.commhub.application.service.support;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.mapper.ProviderSubmissionMapper;
import uz.hamkorbank.commhub.application.port.out.ClockPort;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.provider.EmailProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderAck;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderPort;
import uz.hamkorbank.commhub.application.port.out.provider.ProviderTemplateBinding;
import uz.hamkorbank.commhub.application.port.out.provider.SmsProviderPort;
import uz.hamkorbank.commhub.domain.model.DeliveryAttempt;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ErrorClass;
import uz.hamkorbank.commhub.domain.model.vo.ProviderMessageId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Resolves the adapter of a routed provider and hands the message over (MP-05, AR-04, AD-04).
 *
 * <p>This is the single place where a {@code ProviderRef} produced by the {@code Router} becomes a
 * concrete integration: the adapter whose {@code adapterType} matches. Adapters are discovered
 * through the container, so a new provider is a new bean and nothing else (AR-04).
 *
 * <p>Adapter failures never escape as exceptions: an unexpected error becomes a retryable
 * {@link ProviderAck}, so the sending saga keeps its single decision path (PR-01).
 *
 * <p>Push is the one channel whose submission is not one call: a recipient has as many addresses as
 * registered devices, so that branch is delegated to {@link PushFanOut}, which sends per token and
 * returns the single aggregated answer the saga expects (PU-09).
 */
@Component
public class ProviderGateway {

    private static final String UNEXPECTED_ERROR_CODE = "ADAPTER_ERROR";

    private final ObjectProvider<SmsProviderPort> smsPorts;
    private final ObjectProvider<EmailProviderPort> emailPorts;
    private final PushFanOut pushFanOut;
    private final ProviderSubmissionMapper mapper;
    private final ProviderMessageIdFactory providerMessageIds;
    private final MetricsPort metrics;
    private final ClockPort clock;

    public ProviderGateway(
            ObjectProvider<SmsProviderPort> smsPorts,
            ObjectProvider<EmailProviderPort> emailPorts,
            PushFanOut pushFanOut,
            ProviderSubmissionMapper mapper,
            ProviderMessageIdFactory providerMessageIds,
            MetricsPort metrics,
            ClockPort clock) {
        this.smsPorts = Guard.notNull(smsPorts, "smsPorts");
        this.emailPorts = Guard.notNull(emailPorts, "emailPorts");
        this.pushFanOut = Guard.notNull(pushFanOut, "pushFanOut");
        this.mapper = Guard.notNull(mapper, "mapper");
        this.providerMessageIds = Guard.notNull(providerMessageIds, "providerMessageIds");
        this.metrics = Guard.notNull(metrics, "metrics");
        this.clock = Guard.notNull(clock, "clock");
    }

    /** Whether an adapter is deployed for the routed provider. */
    public boolean supports(ProviderRef provider) {
        return provider != null && adapterExists(provider);
    }

    /**
     * Identifier the Hub assigns to the message on the provider side; providers that assign their own
     * ignore it (§9.1, §9.2).
     */
    public ProviderMessageId providerMessageIdFor(Message message) {
        return providerMessageIds.create(null, message.id());
    }

    /**
     * Submits the message to its routed provider.
     *
     * @param attempt attempt the saga opened for this call; it carries the provider-side id the Hub
     *     generated (§9.1) and is what the per-device rows of a push fan-out point at (PU-09)
     * @param binding provider-side template to use instead of the text; {@code null} when not mapped
     */
    public ProviderAck submit(
            Message message, ProviderRef provider, DeliveryAttempt attempt, ProviderTemplateBinding binding) {
        Guard.notNull(message, "message");
        Guard.notNull(provider, "provider");
        Guard.notNull(attempt, "attempt");
        long startedAt = System.nanoTime();
        ProviderAck ack = call(message, provider, attempt, binding);
        metrics.providerCall(provider, ack.result(), Duration.ofNanos(System.nanoTime() - startedAt));
        return ack;
    }

    /**
     * Timed here and not in the adapters (PR-03, OBS-01).
     *
     * <p>What the saga waits for is the answer, including the retries and the throttle wait the framework
     * of Phase 7 puts around the HTTP call and including the fan-out of a push message to several devices
     * (PU-09). Measuring inside an adapter would time the last HTTP request instead.
     */
    private ProviderAck call(
            Message message, ProviderRef provider, DeliveryAttempt attempt, ProviderTemplateBinding binding) {
        try {
            return switch (provider.channel()) {
                case SMS ->
                    submitSms(message, provider, attempt.providerMessageId().orElse(null), binding);
                case EMAIL -> submitEmail(message, provider);
                case PUSH -> pushFanOut.submit(message, provider, attempt);
            };
        } catch (RuntimeException e) {
            return ProviderAck.failed(
                    UNEXPECTED_ERROR_CODE,
                    ErrorClass.RETRYABLE,
                    "%s: %s".formatted(e.getClass().getSimpleName(), e.getMessage()),
                    clock.now());
        }
    }

    private ProviderAck submitSms(
            Message message,
            ProviderRef provider,
            ProviderMessageId providerMessageId,
            ProviderTemplateBinding binding) {
        return port(smsPorts, provider)
                .map(adapter -> adapter.submit(mapper.toSmsSubmission(message, provider, providerMessageId, binding)))
                .orElseGet(() -> noAdapter(provider));
    }

    private ProviderAck submitEmail(Message message, ProviderRef provider) {
        return port(emailPorts, provider)
                .map(adapter -> adapter.submit(mapper.toEmailSubmission(message, provider)))
                .orElseGet(() -> noAdapter(provider));
    }

    private <T extends ProviderPort> Optional<T> port(ObjectProvider<T> ports, ProviderRef provider) {
        return ports.stream().filter(port -> port.supports(provider)).findFirst();
    }

    private boolean adapterExists(ProviderRef provider) {
        return switch (provider.channel()) {
            case SMS -> smsPorts.stream().anyMatch(port -> port.supports(provider));
            case EMAIL -> emailPorts.stream().anyMatch(port -> port.supports(provider));
            case PUSH -> pushFanOut.supports(provider);
        };
    }

    private ProviderAck noAdapter(ProviderRef provider) {
        return ProviderAck.failed(
                UNEXPECTED_ERROR_CODE,
                ErrorClass.NON_RETRYABLE,
                "no adapter deployed for provider %s (%s)"
                        .formatted(
                                provider.code().value(), provider.adapterType().value()),
                clock.now());
    }

    /** Channel of a routed provider; kept for readability at the call sites. */
    public static Channel channelOf(ProviderRef provider) {
        return Guard.notNull(provider, "provider").channel();
    }
}
