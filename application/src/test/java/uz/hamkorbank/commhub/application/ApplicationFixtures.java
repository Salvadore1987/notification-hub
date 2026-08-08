package uz.hamkorbank.commhub.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import uz.hamkorbank.commhub.application.port.in.command.SubmitMessageCommand;
import uz.hamkorbank.commhub.domain.model.ChannelConfig;
import uz.hamkorbank.commhub.domain.model.Message;
import uz.hamkorbank.commhub.domain.model.MessageEnvelope;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.content.PushContent;
import uz.hamkorbank.commhub.domain.model.content.SmsContent;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.ExternalMessageId;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.service.RoutingConfiguration;

/** Shared test data for the application unit tests. */
public final class ApplicationFixtures {

    public static final Instant NOW = Instant.parse("2026-08-08T10:15:30Z");
    public static final StreamId STREAM_ID = StreamId.of("mobile-app");
    public static final ExternalMessageId EXTERNAL_ID = ExternalMessageId.of("abc0000001");

    private ApplicationFixtures() {}

    public static Msisdn msisdn() {
        return Msisdn.of("998901234567");
    }

    public static Recipient recipient() {
        return Recipient.ofMsisdn(msisdn());
    }

    public static MessageContents smsContents() {
        return MessageContents.of(SmsContent.of("Kod: 123456", "HAMKORBANK"));
    }

    /** Active REST stream with SMS as its default channel and unlimited quotas. */
    public static Stream stream() {
        return Stream.register(
                STREAM_ID,
                "Mobile application",
                IntegrationType.REST,
                Stream.Defaults.of(Channel.SMS, TrafficClass.TRANSACTIONAL));
    }

    public static SubmitMessageCommand submitCommand() {
        return SubmitMessageCommand.of(STREAM_ID, EXTERNAL_ID, recipient(), smsContents());
    }

    /** SMS message in status {@code ACCEPTED}, as the pipeline builds it. */
    public static Message smsMessage() {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, EXTERNAL_ID, TrafficClass.TRANSACTIONAL),
                recipient(),
                SmsContent.of("Kod: 123456", "HAMKORBANK"),
                NOW);
    }

    /** Recipient with the given device tokens and a client id, as a push submission carries them. */
    public static Recipient pushRecipient(PushToken... tokens) {
        return new Recipient(ClientId.of("C123"), null, null, List.of(tokens));
    }

    public static PushToken androidToken(String value) {
        return PushToken.of(value, PushPlatform.ANDROID);
    }

    public static PushToken iosToken(String value) {
        return PushToken.of(value, PushPlatform.IOS);
    }

    /** Push message in status {@code ACCEPTED}, addressed to every device of the recipient (PU-09). */
    public static Message pushMessage(Recipient recipient) {
        return Message.acceptSingleChannel(
                MessageEnvelope.single(STREAM_ID, EXTERNAL_ID, TrafficClass.NOTIFICATION),
                recipient,
                PushContent.of("Hamkorbank", "Sizning hisobingiz to'ldirildi"),
                NOW);
    }

    public static Provider pushProvider(String code, String adapterType) {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of(code),
                Channel.PUSH,
                AdapterType.of(adapterType),
                Provider.Settings.defaults());
    }

    public static Provider smsProvider(String code) {
        return Provider.register(
                ProviderId.newId(),
                ProviderCode.of(code),
                Channel.SMS,
                AdapterType.of(code.toLowerCase(Locale.ROOT) + "-http"),
                Provider.Settings.defaults().withTariff(Tariff.perSegment(Money.of("120", "UZS"))));
    }

    public static ChannelConfig smsChannel(List<Provider> providers) {
        ChannelConfig channelConfig = ChannelConfig.of(Channel.SMS, BalancingStrategy.PRIMARY_ONLY);
        channelConfig.updateFallbackOrder(providers.stream().map(Provider::ref).toList());
        return channelConfig;
    }

    public static RoutingConfiguration routingConfiguration(List<Provider> providers) {
        return RoutingConfiguration.of(Map.of(Channel.SMS, smsChannel(providers)), providers, List.of())
                .withStreamDefaults(Stream.Defaults.of(Channel.SMS, TrafficClass.TRANSACTIONAL));
    }
}
