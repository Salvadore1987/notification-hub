package uz.hamkorbank.commhub.support;

import java.util.List;
import uz.hamkorbank.commhub.application.port.out.ProviderConfigRepository;
import uz.hamkorbank.commhub.application.port.out.StreamRepository;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * The configuration a contour is given before it is handed over: streams, a channel, providers, a
 * routing policy (QA-08, §10.1).
 *
 * <p>Written through the repositories rather than through SQL on purpose. The acceptance scenarios are
 * about a Hub somebody configured, and a fixture that inserted rows by hand could describe a state the
 * administration use cases can never produce.
 */
public final class HubConfiguration {

    public static final StreamId OTP_STREAM = StreamId.of("ibank-otp");
    public static final StreamId TRANSACTIONAL_STREAM = StreamId.of("core-banking");
    public static final StreamId BULK_STREAM = StreamId.of("marketing-bulk");

    public static final ProviderCode PRIMARY = ProviderCode.of("PLAYMOBILE");
    public static final ProviderCode RESERVE = ProviderCode.of("SMSGATE");

    private HubConfiguration() {}

    /**
     * Two SMS providers with the primary first in the fallback order, one channel, three streams.
     *
     * <p>{@code PRIMARY_ONLY} rather than a balancing strategy: an acceptance scenario has to be able to
     * say which provider got the message, and a round robin over two of them cannot.
     */
    public static void seed(ProviderConfigRepository providers, StreamRepository streams) {
        Provider primary = providers.save(provider(PRIMARY, "playmobile-http"));
        Provider reserve = providers.save(provider(RESERVE, "smsgate-http"));

        ChannelConfig sms = ChannelConfig.of(Channel.SMS, BalancingStrategy.PRIMARY_ONLY);
        sms.updateFallbackOrder(List.of(primary.ref(), reserve.ref()));
        providers.save(sms);

        providers.save(RoutingPolicy.of(
                RoutingPolicyId.newId(), RoutingPolicy.Match.any(), RoutingPolicy.Action.toChannel(Channel.SMS), 10));

        streams.save(stream(OTP_STREAM, "iBank OTP", TrafficClass.CRITICAL_OTP));
        streams.save(stream(TRANSACTIONAL_STREAM, "Core banking", TrafficClass.TRANSACTIONAL));
        streams.save(stream(BULK_STREAM, "Marketing", TrafficClass.NOTIFICATION));
    }

    private static Stream stream(StreamId id, String name, TrafficClass trafficClass) {
        return Stream.register(id, name, Stream.Defaults.of(Channel.SMS, trafficClass));
    }

    private static Provider provider(ProviderCode code, String adapterType) {
        return Provider.register(
                ProviderId.newId(),
                code,
                Channel.SMS,
                AdapterType.of(adapterType),
                new Provider.Settings(10, Tariff.perSegment(Money.of("120.5000", "UZS")), RateLimit.unlimited(), true));
    }
}
