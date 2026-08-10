package uz.hamkorbank.commhub.adapter.in.admin;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.hamkorbank.commhub.adapter.in.admin.dto.StatisticsRowResponse;
import uz.hamkorbank.commhub.adapter.in.admin.mapper.AdminViewMapper;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminAuthority;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminPeriod;
import uz.hamkorbank.commhub.adapter.in.admin.support.AdminValues;
import uz.hamkorbank.commhub.adapter.in.admin.support.CsvRenderer;
import uz.hamkorbank.commhub.application.dto.StatisticsRowView;
import uz.hamkorbank.commhub.application.port.in.GetStatistics;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsDimension;
import uz.hamkorbank.commhub.application.port.in.query.StatisticsQuery;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.BatchId;
import uz.hamkorbank.commhub.domain.model.vo.ProviderCode;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reports over the sends of a period (§11.2 "Статистика/Отчёты", FR-6.2, FR-7.4, ANALYST+).
 *
 * <p>The report and its export are one query. An export that read differently from the screen is an
 * export nobody can reconcile with what they saw, so the only thing the CSV endpoint adds is the
 * rendering — which is where a format belongs.
 *
 * <p>{@code includeTest} defaults to false, which is FR-7.4 honoured as a dimension rather than a
 * deletion: a test send stays in the data and stays visible to whoever ran it, and it is out of the
 * report unless somebody asks for it.
 *
 * <p>XLSX is deliberately not produced. §11.2 asks for "CSV/XLSX", and every spreadsheet the Bank uses
 * opens the CSV this endpoint writes — with the byte order mark that makes Excel read it as UTF-8. A
 * second binary format would be a second rendering of the same rows and a library to keep patched, for
 * a file that ends up in the same place.
 */
@RestController
@RequestMapping(AdminApi.STATISTICS)
public class StatisticsController {

    private static final List<String> CSV_HEADER = List.of(
            "key", "accepted", "delivered", "failed", "rejected", "inFlight", "segments", "cost", "deliveryRate");

    private final GetStatistics statistics;
    private final AdminPeriod period;
    private final AdminViewMapper mapper;

    public StatisticsController(GetStatistics statistics, AdminPeriod period, AdminViewMapper mapper) {
        this.statistics = Guard.notNull(statistics, "statistics");
        this.period = Guard.notNull(period, "period");
        this.mapper = Guard.notNull(mapper, "mapper");
    }

    /** {@code GET /api/admin/v1/statistics} — one report, grouped by the requested dimension (FR-6.2). */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(AdminAuthority.ANALYST)
    public List<StatisticsRowResponse> report(
            @RequestParam(defaultValue = "CHANNEL") String dimension,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Filters filters) {
        return mapper.toStatisticsRows(statistics.report(query(dimension, from, to, filters)));
    }

    /** {@code GET /api/admin/v1/statistics/export} — the same rows as CSV (§11.2). */
    @GetMapping(path = "/export", produces = AdminApi.TEXT_CSV)
    @PreAuthorize(AdminAuthority.ANALYST)
    public ResponseEntity<String> export(
            @RequestParam(defaultValue = "CHANNEL") String dimension,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Filters filters) {
        List<StatisticsRowView> rows = statistics.report(query(dimension, from, to, filters));
        String csv = CsvRenderer.render(CSV_HEADER, rows, StatisticsController::toCells);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"commhub-statistics.csv\"")
                .contentType(MediaType.parseMediaType(AdminApi.TEXT_CSV + ";charset=UTF-8"))
                .body(csv);
    }

    private StatisticsQuery query(String dimension, String from, String to, Filters filters) {
        AdminPeriod.Period resolved = period.resolve(from, to);
        return new StatisticsQuery(
                resolved.from(),
                resolved.to(),
                AdminValues.requiredEnum(StatisticsDimension.class, dimension, "dimension"),
                AdminValues.optionalEnum(Channel.class, filters.channel(), "channel"),
                AdminValues.parse(filters.streamId(), "streamId", StreamId::of),
                AdminValues.parse(filters.provider(), "provider", ProviderCode::of),
                filters.batchId() == null || filters.batchId().isBlank()
                        ? null
                        : BatchId.of(AdminValues.requiredUuid(filters.batchId(), "batchId")),
                filters.includeTest());
    }

    private static List<String> toCells(StatisticsRowView row) {
        return java.util.Arrays.asList(
                row.key(),
                Long.toString(row.accepted()),
                Long.toString(row.delivered()),
                Long.toString(row.failed()),
                Long.toString(row.rejected()),
                Long.toString(row.inFlight()),
                Long.toString(row.segments()),
                row.cost() == null ? "" : row.cost().toString(),
                String.format(java.util.Locale.ROOT, "%.4f", row.deliveryRate()));
    }

    /**
     * The narrowing filters, grouped so the handler stays inside the eight parameters this project allows.
     *
     * <p>{@code includeTest} is boxed and defaulted for the same reason the request bodies of this API
     * box their optional flags: an absent query parameter is {@code null}, and Spring's binder refuses
     * to convert {@code null} to a primitive — the whole screen would answer 500 to the request every
     * operator makes first, which is the one with no parameters at all (FR-7.4: a test send is a
     * dimension, and its default is "not shown").
     */
    public record Filters(String channel, String streamId, String provider, String batchId, Boolean includeTest) {

        public Filters {
            includeTest = includeTest != null && includeTest;
        }
    }
}
