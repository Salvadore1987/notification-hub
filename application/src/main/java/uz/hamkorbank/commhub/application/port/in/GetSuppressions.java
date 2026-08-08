package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.SuppressionCheckView;
import uz.hamkorbank.commhub.application.dto.SuppressionView;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionCheckQuery;
import uz.hamkorbank.commhub.application.port.in.query.SuppressionQuery;

/**
 * Read side of the suppression list (FR-5.1, §11.2).
 *
 * <p>Separate from {@link ManageSuppressions} because these open a read-only transaction and return views —
 * the same split as {@code GetTemplates}/{@code ManageTemplates}.
 */
public interface GetSuppressions {

    /** A page of the list for the administration screens (FR-5.1, UI-03). */
    List<SuppressionView> list(SuppressionQuery query);

    /**
     * Whether this recipient may be sent to on this channel, and which entry says otherwise (FR-5.1).
     *
     * <p>The question a source system asks before it builds a campaign and the question support asks when a
     * client says "I stopped getting your messages". Answered by the same lookup the pipeline does, so the
     * answer cannot drift from what sending will decide.
     */
    SuppressionCheckView check(SuppressionCheckQuery query);
}
