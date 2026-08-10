package uz.hamkorbank.commhub.application.port.in.query;

import java.util.List;
import java.util.Map;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TrafficClass;
import uz.hamkorbank.commhub.domain.model.vo.Recipient;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * What a panel-initiated send would cost, before it is sent (ADR-0038, FR-4.4).
 *
 * <p>A single send is this query with one row: the operator confirming one message and the operator
 * confirming fifty thousand are looking at the same numbers.
 *
 * @param rows recipients with the merge variables of each; per-row, because the whole point of a mail
 *     merge is that the rows differ
 */
public record SendEstimateQuery(
        StreamId streamId,
        TemplateCode template,
        ContentLocale locale,
        Channel channel,
        TrafficClass trafficClass,
        List<Row> rows) {

    public SendEstimateQuery {
        Guard.notNull(streamId, "SendEstimateQuery.streamId");
        Guard.notNull(template, "SendEstimateQuery.template");
        Guard.notNull(locale, "SendEstimateQuery.locale");
        Guard.notNull(channel, "SendEstimateQuery.channel");
        rows = Guard.copyOf(rows);
        Guard.isTrue(!rows.isEmpty(), "SendEstimateQuery.rows must not be empty");
    }

    /** One recipient of the planned send and the variables their message is rendered with. */
    public record Row(Recipient recipient, Map<String, String> variables) {

        public Row {
            Guard.notNull(recipient, "Row.recipient");
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }
}
