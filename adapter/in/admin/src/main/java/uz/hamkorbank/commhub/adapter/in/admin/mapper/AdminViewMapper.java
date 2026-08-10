package uz.hamkorbank.commhub.adapter.in.admin.mapper;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.adapter.in.admin.dto.AuditEntryResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ChannelResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.DashboardResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.DlqActionResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.DlqEntryResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ImportResultResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.KillSwitchResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.MessageDigestResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.ProviderResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.QuietHoursDto;
import uz.hamkorbank.commhub.adapter.in.admin.dto.QuotaDto;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RateLimitDto;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RouteEvaluationResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.RoutingPolicyResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SendBatchResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SendEstimateResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.StatisticsRowResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.StreamResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SuppressionCheckResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SuppressionResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.SystemParameterResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateImportResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplatePreviewResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateSummaryResponse;
import uz.hamkorbank.commhub.adapter.in.admin.dto.TemplateVersionResponse;
import uz.hamkorbank.commhub.application.dto.ArchiveDlqResult;
import uz.hamkorbank.commhub.application.dto.AuditEntryView;
import uz.hamkorbank.commhub.application.dto.BatchView;
import uz.hamkorbank.commhub.application.dto.ChannelView;
import uz.hamkorbank.commhub.application.dto.DashboardView;
import uz.hamkorbank.commhub.application.dto.DlqEntryView;
import uz.hamkorbank.commhub.application.dto.KillSwitchResult;
import uz.hamkorbank.commhub.application.dto.MessageDigestView;
import uz.hamkorbank.commhub.application.dto.OperatorBatchResult;
import uz.hamkorbank.commhub.application.dto.ProviderView;
import uz.hamkorbank.commhub.application.dto.ResendDlqResult;
import uz.hamkorbank.commhub.application.dto.RouteEvaluationView;
import uz.hamkorbank.commhub.application.dto.RoutingPolicyView;
import uz.hamkorbank.commhub.application.dto.SendEstimateView;
import uz.hamkorbank.commhub.application.dto.StatisticsRowView;
import uz.hamkorbank.commhub.application.dto.StreamView;
import uz.hamkorbank.commhub.application.dto.SuppressionCheckView;
import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.application.dto.SystemParameterView;
import uz.hamkorbank.commhub.application.dto.TemplateImportResult;
import uz.hamkorbank.commhub.application.dto.TemplatePreviewView;
import uz.hamkorbank.commhub.application.dto.TemplateSummary;
import uz.hamkorbank.commhub.application.dto.TemplateVersionView;
import uz.hamkorbank.commhub.application.dto.TemplateView;
import uz.hamkorbank.commhub.domain.model.QuietHours;
import uz.hamkorbank.commhub.domain.model.QuotaConfig;
import uz.hamkorbank.commhub.domain.model.RateLimit;
import uz.hamkorbank.commhub.domain.model.RoutingPolicy;
import uz.hamkorbank.commhub.domain.model.Stream;
import uz.hamkorbank.commhub.domain.model.Tariff;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.service.SmsSegmentation;

/**
 * Application read models → the response bodies of the admin BFF (§11.2, AR-06).
 *
 * <p>Default methods rather than generated mappings, for the same reason the rest of this project
 * writes them by hand: the views expose {@code Optional} state and value objects that the MapStruct
 * generator cannot introspect. The rule the mapper upholds is the one that matters — no controller
 * builds a response itself.
 *
 * <p>One mapper for every section rather than one per screen. These conversions are all the same
 * conversion — a value object to the string the contract publishes — and splitting them by screen would
 * mean the same {@code Money} rendered two ways the first time somebody edited only one file.
 *
 * <p>The recipient of a message row is <em>not</em> masked here. Masking depends on who is asking, this
 * mapper has no caller, and a mapper that quietly took a decision about personal data would be the last
 * place anybody looked for it. The controller applies {@code AdminMasking} and passes in the result.
 */
@Mapper(componentModel = "spring")
public interface AdminViewMapper {

    // ---------------------------------------------------------------- dashboard and statistics

    default DashboardResponse toDashboard(DashboardView view) {
        return new DashboardResponse(
                view.from().toString(),
                view.to().toString(),
                new DashboardResponse.TotalsResponse(
                        view.totals().accepted(),
                        view.totals().delivered(),
                        view.totals().failed(),
                        view.totals().rejected(),
                        view.totals().inFlight(),
                        view.totals().segments(),
                        text(view.totals().cost()),
                        view.totals().deliveryRate()),
                view.byChannel().stream().map(AdminViewMapper::toStatisticsRow).toList(),
                view.providers().stream().map(AdminViewMapper::toProviderHealth).toList(),
                new DashboardResponse.BacklogResponse(
                        view.backlog().dlqPending(),
                        view.backlog().activeBatches().stream()
                                .map(AdminViewMapper::toBatchSummary)
                                .toList()),
                view.otpLatencyP99Millis(),
                toKillSwitch(view.killSwitch()));
    }

    default List<StatisticsRowResponse> toStatisticsRows(List<StatisticsRowView> rows) {
        return rows.stream().map(AdminViewMapper::toStatisticsRow).toList();
    }

    // ---------------------------------------------------------------- messages, batches, DLQ

    /**
     * One message row.
     *
     * @param recipient already reduced to what the calling role may see, or {@code null}
     */
    default MessageDigestResponse toMessageDigest(MessageDigestView view, String recipient) {
        MessageDigestView.Routing routing = view.routing();
        return new MessageDigestResponse(
                view.messageId().toString(),
                view.streamId().value(),
                view.externalMessageId().value(),
                name(view.channel()),
                view.status().name(),
                recipient,
                view.acceptedAt().toString(),
                new MessageDigestResponse.RoutingResponse(
                        routing.provider() == null ? null : routing.provider().value(),
                        name(routing.channel()),
                        name(routing.reason()),
                        routing.batchId() == null ? null : routing.batchId().toString(),
                        routing.correlationId() == null
                                ? null
                                : routing.correlationId().value(),
                        text(routing.cost()),
                        routing.segments(),
                        text(routing.terminalAt())));
    }

    default DlqEntryResponse toDlqEntry(DlqEntryView view) {
        return new DlqEntryResponse(
                view.messageId().toString(),
                view.reason().name(),
                view.lastError(),
                view.movedAt().toString(),
                view.retriedBy(),
                text(view.retriedAt()),
                view.archived(),
                view.retryable());
    }

    default DlqActionResponse toDlqAction(ResendDlqResult result) {
        return new DlqActionResponse(
                result.requeued().stream().map(Object::toString).toList(),
                result.skipped().stream().map(Object::toString).toList());
    }

    default DlqActionResponse toDlqAction(ArchiveDlqResult result) {
        return new DlqActionResponse(
                result.archived().stream().map(Object::toString).toList(),
                result.skipped().stream().map(Object::toString).toList());
    }

    // ---------------------------------------------------------------- configuration

    default StreamResponse toStream(StreamView view) {
        return new StreamResponse(
                view.streamId().value(),
                view.name(),
                view.integrationType().name(),
                view.status().name(),
                view.connectionStatus().name(),
                toStreamDefaults(view.defaults()),
                new StreamResponse.LimitsDto(
                        toQuota(view.limits().quota()),
                        toRateLimit(view.limits().rateLimit()),
                        toQuietHours(view.limits().quietHours())),
                text(view.lastActivityAt()));
    }

    default ChannelResponse toChannel(ChannelView view) {
        return new ChannelResponse(
                view.channel().name(),
                view.status().name(),
                name(view.balancingStrategy()),
                view.fallbackOrder().stream().map(ProviderCode::value).toList(),
                toQuietHours(view.quietHours()),
                toQuota(view.quota()),
                view.available());
    }

    default ProviderResponse toProvider(ProviderView view) {
        return new ProviderResponse(
                view.providerId().toString(),
                view.code().value(),
                view.channel().name(),
                view.adapterType().value(),
                view.weight(),
                toTariff(view.tariff()),
                toRateLimit(view.rateLimit()),
                new ProviderResponse.StateResponse(
                        view.state().enabled(),
                        view.state().maintenance(),
                        view.state().health().name(),
                        view.state().selectable(),
                        toQuota(view.state().quota()),
                        view.state().credentialsRef(),
                        view.state().endpointConfig()));
    }

    default RoutingPolicyResponse toRoutingPolicy(RoutingPolicyView view) {
        RoutingPolicy.Match match = view.match();
        RoutingPolicy.Action action = view.action();
        return new RoutingPolicyResponse(
                view.policyId().toString(),
                new RoutingPolicyResponse.MatchDto(
                        match.streamId() == null ? null : match.streamId().value(),
                        name(match.trafficClass()),
                        name(match.minPriority()),
                        name(match.channel())),
                new RoutingPolicyResponse.ActionDto(
                        name(action.channel()),
                        action.providerOrder().stream().map(ProviderCode::value).toList(),
                        name(action.balancingStrategy())),
                view.priority(),
                view.enabled());
    }

    default RouteEvaluationResponse toRouteEvaluation(RouteEvaluationView view) {
        return new RouteEvaluationResponse(
                view.routed(),
                name(view.channel()),
                view.provider() == null ? null : view.provider().value(),
                view.fallbackProviders().stream().map(ProviderCode::value).toList(),
                name(view.strategy()),
                view.segments(),
                text(view.estimatedCost()),
                view.rejection() == null
                        ? null
                        : new RouteEvaluationResponse.RejectionDto(
                                view.rejection().reason().name(),
                                view.rejection().detail()));
    }

    // ---------------------------------------------------------------- templates

    default TemplateResponse toTemplate(TemplateView view) {
        return new TemplateResponse(
                view.templateId().toString(),
                view.code().value(),
                view.channel().name(),
                view.direction(),
                view.owner(),
                view.catalogStatus().name(),
                view.versions().stream().map(this::toTemplateVersion).toList(),
                view.providerMappings().stream()
                        .map(AdminViewMapper::toProviderMapping)
                        .toList());
    }

    default TemplateSummaryResponse toTemplateSummary(TemplateSummary summary) {
        return new TemplateSummaryResponse(
                summary.templateId().toString(),
                summary.code().value(),
                summary.channel().name(),
                summary.direction(),
                summary.owner(),
                summary.catalogStatus().name(),
                summary.publishedLocales().stream().map(Enum::name).toList());
    }

    default TemplatePreviewResponse toTemplatePreview(TemplatePreviewView view) {
        SmsSegmentation segmentation = view.segmentation();
        return new TemplatePreviewResponse(
                view.code().value(),
                view.channel().name(),
                view.locale().name(),
                new TemplatePreviewResponse.VersionDto(
                        view.version().number(), view.version().status().name()),
                new TemplateVersionResponse.BodyDto(
                        view.rendered().subject(),
                        view.rendered().text(),
                        view.rendered().html()),
                view.missingVariables(),
                view.costs().stream()
                        .map(cost -> new TemplatePreviewResponse.ProviderCostDto(
                                cost.providerCode().value(), text(cost.cost()), cost.selectable()))
                        .toList(),
                segmentation == null
                        ? null
                        : new TemplatePreviewResponse.SegmentationDto(
                                segmentation.encoding().name(),
                                segmentation.characterCount(),
                                segmentation.segments()));
    }

    default TemplateImportResponse toTemplateImport(TemplateImportResult result) {
        return new TemplateImportResponse(
                result.created(),
                result.imported(),
                result.skipped(),
                result.failures().stream()
                        .map(failure -> new TemplateImportResponse.FailureDto(
                                failure.code(), failure.locale(), failure.reason()))
                        .toList());
    }

    // ---------------------------------------------------------------- suppression, audit, administration

    default SuppressionResponse toSuppression(SuppressionView view) {
        return new SuppressionResponse(
                view.entryId().toString(),
                name(view.channel()),
                view.addressHash() == null ? null : view.addressHash().value(),
                view.clientId() == null ? null : view.clientId().value(),
                view.reason().name(),
                text(view.validUntil()),
                view.createdBy(),
                view.createdAt().toString());
    }

    default SuppressionCheckResponse toSuppressionCheck(SuppressionCheckView view) {
        return new SuppressionCheckResponse(
                view.channel().name(), view.suppressed(), view.entry() == null ? null : toSuppression(view.entry()));
    }

    default AuditEntryResponse toAuditEntry(AuditEntryView view) {
        return new AuditEntryResponse(
                view.occurredAt().toString(),
                view.username(),
                view.action(),
                view.entityType(),
                view.entityId(),
                new AuditEntryResponse.Change(view.before(), view.after()),
                view.reason(),
                view.sourceIp());
    }

    /** The estimate as the confirmation dialog shows it (ADR-0038, FR-4.4). */
    default SendEstimateResponse toSendEstimate(SendEstimateView view, List<ImportResultResponse.FailureDto> failures) {
        return new SendEstimateResponse(
                view.recipients(),
                view.segments(),
                view.estimatedCostOptional()
                        .map(money -> money.amount().toPlainString())
                        .orElse(null),
                view.providerOptional().map(ProviderCode::value).orElse(null),
                new SendEstimateResponse.TemplateVersionDto(
                        view.template().number(), view.template().status().name()),
                view.missingVariables(),
                view.rejectionOptional()
                        .map(rejection -> new SendEstimateResponse.RejectionDto(
                                rejection.reason().name(), rejection.detail()))
                        .orElse(null),
                failures);
    }

    /** What came of the batch, plus the rows the file itself could not yield. */
    default SendBatchResponse toSendBatch(OperatorBatchResult result, List<ImportResultResponse.FailureDto> failures) {
        return new SendBatchResponse(
                result.batchId().toString(), result.accepted(), result.duplicates(), result.rejected(), failures);
    }

    default KillSwitchResponse toKillSwitch(KillSwitchResult result) {
        return new KillSwitchResponse(result.active(), result.includesCriticalOtp(), text(result.changedAt()));
    }

    default SystemParameterResponse toSystemParameter(SystemParameterView view) {
        return new SystemParameterResponse(
                view.key(), view.value(), view.description(), text(view.updatedAt()), view.updatedBy());
    }

    // ---------------------------------------------------------------- shared pieces

    default QuotaDto toQuota(QuotaConfig quota) {
        return quota == null
                ? null
                : new QuotaDto(
                        quota.dailyCount(),
                        quota.monthlyCount(),
                        text(quota.dailyCost()),
                        text(quota.monthlyCost()),
                        name(quota.behavior()));
    }

    default QuietHoursDto toQuietHours(QuietHours quietHours) {
        return quietHours == null
                ? null
                : new QuietHoursDto(
                        quietHours.start().toString(),
                        quietHours.end().toString(),
                        quietHours.zone().getId(),
                        quietHours.behavior().name());
    }

    default RateLimitDto toRateLimit(RateLimit rateLimit) {
        return rateLimit == null
                ? null
                : new RateLimitDto(rateLimit.tps(), rateLimit.perMinute(), rateLimit.perRecipientPerHour());
    }

    private static ProviderResponse.TariffDto toTariff(Tariff tariff) {
        return tariff == null
                ? null
                : new ProviderResponse.TariffDto(text(tariff.perMessage()), text(tariff.perSegment()));
    }

    private static StreamResponse.StreamDefaultsDto toStreamDefaults(Stream.Defaults defaults) {
        return new StreamResponse.StreamDefaultsDto(
                name(defaults.channel()),
                defaults.provider() == null ? null : defaults.provider().code().value(),
                name(defaults.trafficClass()),
                name(defaults.priority()),
                name(defaults.balancingStrategy()));
    }

    default TemplateVersionResponse toTemplateVersion(TemplateVersionView view) {
        TemplateVersion.Body body = view.body();
        return new TemplateVersionResponse(
                view.versionId().toString(),
                view.version(),
                view.locale().name(),
                new TemplateVersionResponse.BodyDto(body.subject(), body.text(), body.html()),
                view.status().name(),
                view.variables(),
                new TemplateVersionResponse.ReviewDto(
                        view.review().createdBy(),
                        view.review().reviewedBy(),
                        text(view.review().publishedAt())));
    }

    private static TemplateResponse.ProviderMappingDto toProviderMapping(Template.ProviderMapping mapping) {
        return new TemplateResponse.ProviderMappingDto(
                mapping.providerCode().value(), mapping.providerTemplateId(), mapping.approved());
    }

    private static StatisticsRowResponse toStatisticsRow(StatisticsRowView row) {
        return new StatisticsRowResponse(
                row.key(),
                row.accepted(),
                row.delivered(),
                row.failed(),
                row.rejected(),
                row.inFlight(),
                row.segments(),
                new StatisticsRowResponse.RowCost(text(row.cost()), row.deliveryRate()));
    }

    private static DashboardResponse.ProviderHealthResponse toProviderHealth(DashboardView.ProviderHealthLine line) {
        return new DashboardResponse.ProviderHealthResponse(
                line.provider().value(), line.channel().name(), line.health().name(), line.selectable());
    }

    private static DashboardResponse.BatchSummaryResponse toBatchSummary(BatchView view) {
        return new DashboardResponse.BatchSummaryResponse(
                view.batchId().toString(),
                view.streamId().value(),
                view.channel().name(),
                view.status().name(),
                view.total(),
                view.progress().processed(),
                view.progress().completionPercent(),
                view.createdAt().toString());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String text(Money money) {
        return money == null ? null : money.toString();
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String text(LocalTime time) {
        return time == null ? null : time.toString();
    }
}
