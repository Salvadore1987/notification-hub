package uz.hamkorbank.commhub.application.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.hamkorbank.commhub.application.dto.RouteEvaluationView;
import uz.hamkorbank.commhub.application.dto.SendEstimateView;
import uz.hamkorbank.commhub.application.port.in.EstimateSend;
import uz.hamkorbank.commhub.application.port.in.EvaluateRoute;
import uz.hamkorbank.commhub.application.port.in.query.RouteEvaluationQuery;
import uz.hamkorbank.commhub.application.port.in.query.SendEstimateQuery;
import uz.hamkorbank.commhub.application.service.support.PublishedTemplates;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.vo.Money;
import uz.hamkorbank.commhub.domain.service.SegmentCalculator;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Prices a planned send against the configuration it will actually run on (ADR-0038, FR-4.4).
 *
 * <p>Rows are rendered with the non-strict renderer, so an unfilled merge field is <em>listed</em>
 * instead of throwing: the operator is meant to see what is missing, not to be told the file is bad.
 *
 * <p>Rows are then grouped by their segment count — normally one to three groups — and each group is
 * routed once through the real {@link EvaluateRoute}. That keeps the promise that there is exactly one
 * simulation of the routing rules in this system, and it keeps a fifty-thousand-row estimate down to a
 * handful of routing calls.
 */
@Service
public class SendEstimateService implements EstimateSend {

    private final PublishedTemplates templates;
    private final EvaluateRoute routes;
    private final SegmentCalculator segments;

    public SendEstimateService(PublishedTemplates templates, EvaluateRoute routes, SegmentCalculator segments) {
        this.templates = Guard.notNull(templates, "templates");
        this.routes = Guard.notNull(routes, "routes");
        this.segments = Guard.notNull(segments, "segments");
    }

    @Override
    @Transactional(readOnly = true)
    public SendEstimateView estimate(SendEstimateQuery query) {
        Guard.notNull(query, "query");
        PublishedTemplates.Resolved resolved = templates.require(query.template(), query.channel(), query.locale());
        TemplateVersion version = resolved.version();

        Set<String> missing = new LinkedHashSet<>();
        // Строки с одинаковым числом сегментов стоят одинаково, поэтому маршрут считается на группу,
        // а не на строку: персонализированная рассылка на 50 000 имён — это одна-три группы.
        Map<Integer, GroupOfRows> groups = new LinkedHashMap<>();
        for (SendEstimateQuery.Row row : query.rows()) {
            missing.addAll(version.missingVariables(row.variables()));
            String text = version.renderPreview(row.variables()).text();
            int segmentCount = segments.calculate(text).segments();
            groups.computeIfAbsent(segmentCount, key -> new GroupOfRows(text, row)).count++;
        }

        long recipients = query.rows().size();
        long totalSegments = 0;
        Money cost = null;
        RouteEvaluationView.Rejection rejection = null;
        RouteEvaluationView routed = null;
        for (GroupOfRows group : groups.values()) {
            RouteEvaluationView view = routes.evaluate(new RouteEvaluationQuery(
                    query.streamId(),
                    group.sample.recipient(),
                    query.channel(),
                    query.trafficClass(),
                    null,
                    group.text));
            if (!view.routed()) {
                rejection = view.rejection();
                break;
            }
            routed = view;
            totalSegments += (long) view.segments() * group.count;
            cost = add(cost, view.estimatedCostOptional().map(money -> money.multipliedBy(group.count)));
        }

        return new SendEstimateView(
                query.channel(),
                new SendEstimateView.TemplateVersionRef(version.version(), version.status()),
                recipients,
                rejection == null ? totalSegments : 0,
                rejection == null ? cost : null,
                routed == null ? null : routed.provider(),
                List.copyOf(missing),
                rejection);
    }

    private static Money add(Money total, Optional<Money> addend) {
        if (addend.isEmpty()) {
            return total;
        }
        return total == null ? addend.get() : total.plus(addend.get());
    }

    /** Rows that render to the same text, and therefore cost the same. */
    private static final class GroupOfRows {

        private final String text;
        private final SendEstimateQuery.Row sample;
        private int count;

        private GroupOfRows(String text, SendEstimateQuery.Row sample) {
            this.text = text;
            this.sample = sample;
        }
    }
}
