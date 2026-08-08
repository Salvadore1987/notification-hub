package uz.hamkorbank.commhub.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;

/** Shared test data for the domain unit tests. */
public final class DomainFixtures {

    public static final Instant NOW = Instant.parse("2026-08-08T10:15:30Z");
    public static final StreamId STREAM_ID = StreamId.of("mobile-app");
    public static final Currency UZS = Currency.getInstance("UZS");

    private DomainFixtures() {}

    public static Msisdn msisdn() {
        return Msisdn.of("998901234567");
    }

    public static Money uzs(String amount) {
        return Money.of(amount, "UZS");
    }

    public static MessageEnvelope envelope(String externalId, TrafficClass trafficClass) {
        return MessageEnvelope.single(STREAM_ID, ExternalMessageId.of(externalId), trafficClass);
    }

    /** Single-channel SMS message in status {@code ACCEPTED}. */
    public static Message smsMessage() {
        return Message.acceptSingleChannel(
                envelope("abc0000001", TrafficClass.TRANSACTIONAL),
                Recipient.ofMsisdn(msisdn()),
                SmsContent.of("Hello", "HAMKORBANK"),
                NOW);
    }

    public static Provider smsProvider(String code, int weight, Money perSegment) {
        Provider.Settings settings =
                Provider.Settings.defaults().withWeight(weight).withTariff(Tariff.perSegment(perSegment));
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of(code),
                Channel.SMS,
                AdapterType.of(code.toLowerCase(Locale.ROOT) + "-http"),
                settings);
    }

    public static Provider smsProvider(String code) {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of(code),
                Channel.SMS,
                AdapterType.of(code.toLowerCase(Locale.ROOT) + "-http"),
                Provider.Settings.defaults());
    }

    /** SMS channel whose fallback order follows the given providers. */
    public static ChannelConfig smsChannel(BalancingStrategy strategy, List<Provider> providers) {
        ChannelConfig channelConfig = ChannelConfig.of(Channel.SMS, strategy);
        channelConfig.updateFallbackOrder(providers.stream().map(Provider::ref).toList());
        return channelConfig;
    }

    public static RoutingConfiguration routingConfiguration(
            BalancingStrategy strategy, List<Provider> providers, List<RoutingPolicy> policies) {
        return RoutingConfiguration.of(Map.of(Channel.SMS, smsChannel(strategy, providers)), providers, policies);
    }
}
