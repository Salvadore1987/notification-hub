package uz.hamkorbank.commhub.application.service.pipeline;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;
import uz.hamkorbank.commhub.application.policy.FrequencyCapPolicy;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferencePort;
import uz.hamkorbank.commhub.application.port.out.CustomerPreferences;
import uz.hamkorbank.commhub.application.port.out.FrequencyCounterPort;
import uz.hamkorbank.commhub.application.port.out.MetricsPort;
import uz.hamkorbank.commhub.application.port.out.SuppressionRepository;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AddressHash;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Compliance filters applied to a routed message before it is queued (FR-5.1…FR-5.4).
 *
 * <p>Order matters and follows the SRS: suppression list → client consent → quiet hours → frequency
 * cap. Suppression and consent reject; quiet hours either reject or defer, depending on the
 * configuration of the window (FR-5.3); the frequency cap only counts and alerts in the MVP
 * (FR-5.4).
 *
 * <p>{@code CRITICAL_OTP} and {@code TRANSACTIONAL} are never held by quiet hours or by the frequency
 * cap — that is a property of the traffic class itself (SRS §6.2).
 */
@Component
public class DeliveryFilters {

    private final SuppressionRepository suppressions;
    private final CustomerPreferencePort preferences;
    private final FrequencyCounterPort frequencyCounters;
    private final FrequencyCapPolicy frequencyCapPolicy;
    private final MetricsPort metrics;

    public DeliveryFilters(
            SuppressionRepository suppressions,
            CustomerPreferencePort preferences,
            FrequencyCounterPort frequencyCounters,
            FrequencyCapPolicy frequencyCapPolicy,
            MetricsPort metrics) {
        this.suppressions = Guard.notNull(suppressions, "suppressions");
        this.preferences = Guard.notNull(preferences, "preferences");
        this.frequencyCounters = Guard.notNull(frequencyCounters, "frequencyCounters");
        this.frequencyCapPolicy = Guard.notNull(frequencyCapPolicy, "frequencyCapPolicy");
        this.metrics = Guard.notNull(metrics, "metrics");
    }

    /**
     * Applies every filter to the message on its selected channel.
     *
     * @param channelConfig configuration of the channel; supplies the fallback quiet-hours window
     */
    public FilterVerdict apply(
            Message message, Channel channel, Stream stream, ChannelConfig channelConfig, Instant now) {
        Guard.notNull(message, "message");
        Guard.notNull(channel, "channel");
        Guard.notNull(now, "now");
        TrafficClass trafficClass = message.envelope().trafficClass();

        FilterVerdict suppression = checkSuppression(message.recipient(), channel, now);
        if (!suppression.isPassed()) {
            return suppression;
        }
        FilterVerdict consent = checkConsent(message.recipient(), trafficClass);
        if (!consent.isPassed()) {
            return consent;
        }
        FilterVerdict quietHours = checkQuietHours(trafficClass, stream, channelConfig, now);
        if (!quietHours.isPassed()) {
            return quietHours;
        }
        return checkFrequencyCap(message.recipient(), channel, trafficClass, now);
    }

    /** Suppression list by address and by client (FR-5.1). */
    private FilterVerdict checkSuppression(Recipient recipient, Channel channel, Instant now) {
        Optional<AddressHash> address = addressHash(recipient, channel);
        if (address.isPresent()
                && suppressions.findActiveByAddress(address.get(), channel, now).isPresent()) {
            return FilterVerdict.rejected(RejectionReason.SUPPRESSED, "address is on the suppression list");
        }
        Optional<ClientId> clientId = recipient.clientIdOptional();
        if (clientId.isPresent()
                && suppressions.findActiveByClient(clientId.get(), channel, now).isPresent()) {
            return FilterVerdict.rejected(RejectionReason.SUPPRESSED, "client is on the suppression list");
        }
        return FilterVerdict.passed();
    }

    /**
     * Client consent for non-transactional traffic (FR-5.2).
     *
     * <p>The preference store arrives in phase 2 (FR-8.2); until then the stub adapter answers "no
     * record", which must not block anything.
     */
    private FilterVerdict checkConsent(Recipient recipient, TrafficClass trafficClass) {
        if (trafficClass != TrafficClass.NOTIFICATION) {
            return FilterVerdict.passed();
        }
        return recipient
                .clientIdOptional()
                .flatMap(preferences::find)
                .filter(preference -> !preference.marketingOptIn())
                .map(CustomerPreferences::clientId)
                .map(clientId -> FilterVerdict.rejected(RejectionReason.OPT_OUT, "client opted out of bulk traffic"))
                .orElseGet(FilterVerdict::passed);
    }

    /** Quiet hours of the stream, falling back to the channel window (FR-5.3). */
    private FilterVerdict checkQuietHours(
            TrafficClass trafficClass, Stream stream, ChannelConfig channelConfig, Instant now) {
        if (!trafficClass.respectsQuietHours()) {
            return FilterVerdict.passed();
        }
        Optional<QuietHours> window = stream == null ? Optional.empty() : stream.quietHours();
        if (window.isEmpty() && channelConfig != null) {
            window = channelConfig.quietHours();
        }
        if (window.isEmpty() || !window.get().isQuietAt(now)) {
            return FilterVerdict.passed();
        }
        QuietHours quietHours = window.get();
        if (quietHours.defersDelivery()) {
            return FilterVerdict.deferred(quietHours.nextOpeningAt(now), "held back by quiet hours");
        }
        return FilterVerdict.rejected(RejectionReason.QUIET_HOURS, "submitted inside the quiet-hours window");
    }

    /** Frequency capping of bulk traffic; counts and alerts, blocks only when configured (FR-5.4). */
    private FilterVerdict checkFrequencyCap(
            Recipient recipient, Channel channel, TrafficClass trafficClass, Instant now) {
        if (!trafficClass.respectsFrequencyCapping()) {
            return FilterVerdict.passed();
        }
        Optional<AddressHash> address = addressHash(recipient, channel);
        if (address.isEmpty()) {
            return FilterVerdict.passed();
        }
        long observed = frequencyCounters.countSince(address.get(), channel, now.minus(frequencyCapPolicy.window()));
        if (!frequencyCapPolicy.isExceededBy(observed)) {
            return FilterVerdict.passed();
        }
        metrics.frequencyCapExceeded(channel, observed, frequencyCapPolicy.maxMessages());
        if (!frequencyCapPolicy.blocking()) {
            return FilterVerdict.passed();
        }
        return FilterVerdict.rejected(
                RejectionReason.FREQUENCY_CAPPED,
                "%d messages in the last %s exceed the cap of %d"
                        .formatted(observed, frequencyCapPolicy.window(), frequencyCapPolicy.maxMessages()));
    }

    /** Registers the send against the frequency counters of the recipient (FR-5.4). */
    public void registerSend(Recipient recipient, Channel channel, Instant now) {
        addressHash(recipient, channel).ifPresent(hash -> frequencyCounters.register(hash, channel, now));
    }

    /** Hash of the address the message is delivered to on this channel (DB-04). */
    public static Optional<AddressHash> addressHash(Recipient recipient, Channel channel) {
        Guard.notNull(recipient, "recipient");
        Guard.notNull(channel, "channel");
        return switch (channel) {
            case SMS -> Optional.ofNullable(recipient.msisdn()).map(AddressHash::ofMsisdn);
            case EMAIL -> Optional.ofNullable(recipient.email()).map(AddressHash::ofEmail);
            case PUSH -> recipient.pushTokens().stream().findFirst().map(AddressHash::ofPushToken);
        };
    }
}
