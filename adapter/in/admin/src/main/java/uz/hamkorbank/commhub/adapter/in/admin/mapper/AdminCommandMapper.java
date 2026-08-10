package uz.hamkorbank.commhub.adapter.in.admin.mapper;

import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ChannelRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ProviderRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.QuietHoursDto;
import uz.hamkorbank.commhub.adapter.in.admin.dto.QuotaDto;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RateLimitDto;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RecipientDto;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RouteEvaluationRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RoutingPolicyRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RoutingPolicyResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.StreamRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SuppressionRequest;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TestSendRequest;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.contract.InboundContractException;
import uz.hamkorbank.commhub.application.port.in.command.ConfigureChannelCommand;
import uz.hamkorbank.commhub.application.port.in.command.RegisterProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.RegisterStreamCommand;
import uz.hamkorbank.commhub.application.port.in.command.SaveRoutingPolicyCommand;
import uz.hamkorbank.commhub.application.port.in.command.SendTestMessageCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressAddressCommand;
import uz.hamkorbank.commhub.application.port.in.command.SuppressClientCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateProviderCommand;
import uz.hamkorbank.commhub.application.port.in.command.UpdateStreamCommand;
import uz.hamkorbank.commhub.application.port.in.query.RouteEvaluationQuery;
import uz.hamkorbank.commhub.domain.model.Actor;
import uz.hamkorbank.commhub.domain.model.Provider;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.type.BalancingStrategy;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.IntegrationType;
import uz.hamkorbank.commhub.domain.model.type.Priority;
import uz.hamkorbank.commhub.domain.model.type.PushPlatform;
import uz.hamkorbank.commhub.domain.model.type.QuietHoursBehavior;
import uz.hamkorbank.commhub.domain.model.type.QuotaExhaustionBehavior;
import uz.hamkorbank.commhub.domain.model.type.SuppressionReason;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.AdapterType;
import uz.hamkorbank.commhub.domain.model.vo.ClientId;
import uz.hamkorbank.commhub.domain.model.vo.EmailAddress;
import uz.hamkorbank.commhub.domain.model.vo.Msisdn;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.ProviderId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderRef;
import uz.hamkorbank.commhub.domain.model.vo.PushToken;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.RoutingPolicyId;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;

/**
 * Request bodies of the admin BFF → the commands of the use cases (AR-06).
 *
 * <p>The {@link Actor} is always a parameter and never a field of a request. Who performed a
 * configuration change comes from the authenticated caller (SEC-03, FR-7.3); a body that named its own
 * author would be an audit journal anybody could write into.
 *
 * <p>A {@code null} in an update means "leave this alone", which is why nothing here substitutes a
 * default for an absent block. The one exception is spelled out in the request itself:
 * {@code clearQuietHours}, because for quiet hours "no window" and "unchanged" are both meaningful and
 * an omission cannot mean both.
 */
@Mapper(componentModel = "spring")
public interface AdminCommandMapper {

    // ---------------------------------------------------------------- streams

    default RegisterStreamCommand toRegisterStream(
            String streamId, StreamRequest request, ProviderRef provider, Actor actor) {
        return new RegisterStreamCommand(
                actor,
                AdminValues.parseRequired(streamId, "streamId", StreamId::of),
                request.name(),
                AdminValues.requiredEnum(IntegrationType.class, request.integrationType(), "integrationType"),
                toStreamDefaults(request, provider),
                toQuota(request.quota()),
                toQuietHours(request.quietHours()),
                toRateLimit(request.rateLimit()));
    }

    default UpdateStreamCommand toUpdateStream(
            String streamId, StreamRequest request, ProviderRef provider, Actor actor) {
        return new UpdateStreamCommand(
                actor,
                AdminValues.parseRequired(streamId, "streamId", StreamId::of),
                request.defaults() == null ? null : toStreamDefaults(request, provider),
                toQuota(request.quota()),
                toQuietHours(request.quietHours()),
                request.clearQuietHours(),
                toRateLimit(request.rateLimit()),
                request.credentialsRef());
    }

    // ---------------------------------------------------------------- channels and providers

    default ConfigureChannelCommand toConfigureChannel(String channel, ChannelRequest request, Actor actor) {
        return new ConfigureChannelCommand(
                actor,
                AdminValues.requiredEnum(Channel.class, channel, "channel"),
                AdminValues.requiredEnum(BalancingStrategy.class, request.balancingStrategy(), "balancingStrategy"),
                providerCodes(request.fallbackOrder()),
                toQuietHours(request.quietHours()),
                toQuota(request.quota()));
    }

    default RegisterProviderCommand toRegisterProvider(String code, ProviderRequest request, Actor actor) {
        return new RegisterProviderCommand(
                actor,
                AdminValues.parseRequired(code, "code", ProviderCode::of),
                AdminValues.requiredEnum(Channel.class, request.channel(), "channel"),
                AdminValues.parseRequired(request.adapterType(), "adapterType", AdapterType::of),
                new Provider.Settings(
                        request.weight() == null ? Provider.DEFAULT_WEIGHT : request.weight(),
                        toTariff(request),
                        toRateLimit(request.rateLimit()),
                        request.credentialsRef(),
                        true),
                toQuota(request.quota()),
                request.endpointConfig() == null ? Map.of() : request.endpointConfig());
    }

    default UpdateProviderCommand toUpdateProvider(String providerId, ProviderRequest request, Actor actor) {
        return new UpdateProviderCommand(
                actor,
                ProviderId.of(AdminValues.requiredUuid(providerId, "providerId")),
                request.weight(),
                toTariff(request),
                toRateLimit(request.rateLimit()),
                toQuota(request.quota()),
                request.credentialsRef(),
                request.endpointConfig());
    }

    // ---------------------------------------------------------------- routing

    default SaveRoutingPolicyCommand toSaveRoutingPolicy(String policyId, RoutingPolicyRequest request, Actor actor) {
        RoutingPolicyResponse.MatchDto match = request.match();
        RoutingPolicyResponse.ActionDto action = request.action();
        if (match == null || action == null) {
            throw InboundContractException.missing(match == null ? "match" : "action");
        }
        return new SaveRoutingPolicyCommand(
                actor,
                policyId == null || policyId.isBlank()
                        ? null
                        : RoutingPolicyId.of(AdminValues.requiredUuid(policyId, "policyId")),
                new RoutingPolicy.Match(
                        AdminValues.parse(match.streamId(), "match.streamId", StreamId::of),
                        AdminValues.optionalEnum(TrafficClass.class, match.trafficClass(), "match.trafficClass"),
                        AdminValues.optionalEnum(Priority.class, match.minPriority(), "match.minPriority"),
                        AdminValues.optionalEnum(Channel.class, match.channel(), "match.channel")),
                new RoutingPolicy.Action(
                        AdminValues.optionalEnum(Channel.class, action.channel(), "action.channel"),
                        providerCodes(action.providerOrder()),
                        AdminValues.optionalEnum(
                                BalancingStrategy.class, action.balancingStrategy(), "action.balancingStrategy")),
                request.priority());
    }

    default RouteEvaluationQuery toRouteEvaluation(RouteEvaluationRequest request) {
        return new RouteEvaluationQuery(
                AdminValues.parseRequired(request.streamId(), "streamId", StreamId::of),
                toRecipient(request.recipient()),
                AdminValues.optionalEnum(Channel.class, request.channel(), "channel"),
                AdminValues.optionalEnum(TrafficClass.class, request.trafficClass(), "trafficClass"),
                AdminValues.optionalEnum(Priority.class, request.priority(), "priority"),
                request.text());
    }

    // ---------------------------------------------------------------- suppression and test sends

    default SuppressAddressCommand toSuppressAddress(SuppressionRequest request, Actor actor) {
        return new SuppressAddressCommand(
                actor,
                AdminValues.requiredEnum(Channel.class, request.channel(), "channel"),
                request.address(),
                AdminValues.requiredEnum(SuppressionReason.class, request.reason(), "reason"),
                AdminValues.instant(request.validUntil(), "validUntil"));
    }

    default SuppressClientCommand toSuppressClient(SuppressionRequest request, Actor actor) {
        return new SuppressClientCommand(
                actor,
                AdminValues.optionalEnum(Channel.class, request.channel(), "channel"),
                AdminValues.parseRequired(request.clientId(), "clientId", ClientId::of),
                AdminValues.requiredEnum(SuppressionReason.class, request.reason(), "reason"),
                AdminValues.instant(request.validUntil(), "validUntil"));
    }

    default SendTestMessageCommand toTestSend(TestSendRequest request, Actor actor) {
        return new SendTestMessageCommand(
                actor,
                AdminValues.parseRequired(request.streamId(), "streamId", StreamId::of),
                AdminValues.requiredEnum(Channel.class, request.channel(), "channel"),
                toRecipient(request.recipient()),
                AdminValues.parse(request.provider(), "provider", ProviderCode::of),
                request.text(),
                request.subject());
    }

    // ---------------------------------------------------------------- shared pieces

    default QuotaConfig toQuota(QuotaDto quota) {
        return quota == null
                ? null
                : new QuotaConfig(
                        quota.dailyCount(),
                        quota.monthlyCount(),
                        AdminValues.money(quota.dailyCost(), "quota.dailyCost"),
                        AdminValues.money(quota.monthlyCost(), "quota.monthlyCost"),
                        behaviorOf(quota));
    }

    default QuietHours toQuietHours(QuietHoursDto quietHours) {
        if (quietHours == null) {
            return null;
        }
        return new QuietHours(
                AdminValues.localTime(quietHours.start(), "quietHours.start"),
                AdminValues.localTime(quietHours.end(), "quietHours.end"),
                AdminValues.zone(quietHours.zone(), "quietHours.zone", QuietHours.DEFAULT_ZONE),
                AdminValues.optionalEnum(QuietHoursBehavior.class, quietHours.behavior(), "quietHours.behavior"));
    }

    default RateLimit toRateLimit(RateLimitDto rateLimit) {
        return rateLimit == null
                ? null
                : new RateLimit(rateLimit.tps(), rateLimit.perMinute(), rateLimit.perRecipientPerHour());
    }

    /**
     * The recipient a dry run or a test send is aimed at.
     *
     * <p>Each address goes through its own value object, so a mistyped number is refused here with a
     * field pointer rather than becoming a route evaluation for a recipient that could never exist.
     */
    private static Recipient toRecipient(RecipientDto dto) {
        if (dto == null) {
            throw InboundContractException.missing("recipient");
        }
        Recipient recipient = new Recipient(
                AdminValues.parse(dto.clientId(), "recipient.clientId", ClientId::of),
                AdminValues.parse(dto.msisdn(), "recipient.msisdn", Msisdn::normalize),
                AdminValues.parse(dto.email(), "recipient.email", EmailAddress::of),
                toPushTokens(dto));
        if (recipient.msisdn() == null
                && recipient.email() == null
                && recipient.pushTokens().isEmpty()) {
            throw InboundContractException.missing("recipient.msisdn|recipient.email|recipient.pushToken");
        }
        return recipient;
    }

    /** A token without its platform is unusable: nothing about the string says who issued it (PU-04). */
    private static List<PushToken> toPushTokens(RecipientDto dto) {
        if (dto.pushToken() == null || dto.pushToken().isBlank()) {
            return List.of();
        }
        PushPlatform platform =
                AdminValues.requiredEnum(PushPlatform.class, dto.pushPlatform(), "recipient.pushPlatform");
        return List.of(PushToken.of(dto.pushToken().trim(), platform));
    }

    private static Stream.Defaults toStreamDefaults(StreamRequest request, ProviderRef provider) {
        if (request.defaults() == null) {
            return Stream.Defaults.none();
        }
        return new Stream.Defaults(
                AdminValues.optionalEnum(Channel.class, request.defaults().channel(), "defaults.channel"),
                provider,
                AdminValues.optionalEnum(
                        TrafficClass.class, request.defaults().trafficClass(), "defaults.trafficClass"),
                AdminValues.optionalEnum(Priority.class, request.defaults().priority(), "defaults.priority"),
                AdminValues.optionalEnum(
                        BalancingStrategy.class, request.defaults().balancingStrategy(), "defaults.balancingStrategy"));
    }

    private static Tariff toTariff(ProviderRequest request) {
        if (request.tariff() == null) {
            return null;
        }
        return new Tariff(
                AdminValues.money(request.tariff().perMessage(), "tariff.perMessage"),
                AdminValues.money(request.tariff().perSegment(), "tariff.perSegment"));
    }

    private static QuotaExhaustionBehavior behaviorOf(QuotaDto quota) {
        QuotaExhaustionBehavior behavior =
                AdminValues.optionalEnum(QuotaExhaustionBehavior.class, quota.behavior(), "quota.behavior");
        return behavior == null ? QuotaExhaustionBehavior.ALERT_ONLY : behavior;
    }

    private static List<ProviderCode> providerCodes(List<String> codes) {
        return AdminValues.list(codes).stream()
                .map(code -> AdminValues.parseRequired(code, "providerOrder", ProviderCode::of))
                .toList();
    }
}
