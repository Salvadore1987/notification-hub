package uz.hamkorbank.commhub.application.port.out;

import java.util.Map;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.StreamId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Criteria of an audience to be resolved by {@link AudienceResolverPort} (FR-8.11, §18.5).
 *
 * <p>Part of the reserved contract only; no adapter implements it in the current scope.
 *
 * @param segmentCode identifier of the segment in the Bank's data mart
 * @param parameters free-form segment parameters agreed with the data team
 */
public record AudienceQuery(
        StreamId streamId,
        TemplateCode templateCode,
        Channel channel,
        String segmentCode,
        Map<String, String> parameters) {

    public AudienceQuery {
        Guard.notNull(streamId, "AudienceQuery.streamId");
        Guard.notBlank(segmentCode, "AudienceQuery.segmentCode");
        parameters = Guard.copyOf(parameters);
    }
}
