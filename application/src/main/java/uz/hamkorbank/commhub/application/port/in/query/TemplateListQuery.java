package uz.hamkorbank.commhub.application.port.in.query;

import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.type.TemplateCatalogStatus;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * A page of the template catalogue (FR-4.1, FR-4.6, UI-03).
 *
 * <p>Every filter is optional — {@code null} means "any" — but the page size never is: the catalogue is
 * loaded with the versions of every template attached, so an unbounded listing would read the whole
 * catalogue to draw one screen.
 *
 * @param direction business direction, matched exactly (§18.4)
 */
public record TemplateListQuery(
        Channel channel, String direction, TemplateCatalogStatus catalogStatus, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;

    public TemplateListQuery {
        Guard.isTrue(limit <= MAX_LIMIT, "TemplateListQuery.limit exceeds " + MAX_LIMIT);
        Guard.positive(limit, "TemplateListQuery.limit");
        Guard.notNegative(offset, "TemplateListQuery.offset");
    }

    /** First page of the active catalogue of a channel. */
    public static TemplateListQuery ofChannel(Channel channel) {
        return new TemplateListQuery(channel, null, TemplateCatalogStatus.ACTIVE, DEFAULT_LIMIT, 0);
    }
}
