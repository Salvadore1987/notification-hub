package uz.hamkorbank.commhub.application.port.in;

import java.util.List;
import uz.hamkorbank.commhub.application.dto.TemplatePreviewView;
import uz.hamkorbank.commhub.application.dto.TemplateSummary;
import uz.hamkorbank.commhub.application.dto.TemplateView;
import uz.hamkorbank.commhub.application.port.in.query.TemplateListQuery;
import uz.hamkorbank.commhub.application.port.in.query.TemplatePreviewQuery;
import uz.hamkorbank.commhub.application.port.in.query.TemplateQuery;

/**
 * Read side of the template catalogue, including the SMS preview (FR-4.1, FR-4.4).
 *
 * <p>Separate from {@link ManageTemplates} because these open a read-only transaction and return views —
 * the same split as {@code GetMessage}/{@code SubmitMessage}.
 */
public interface GetTemplates {

    /** One card with all its versions and provider mappings (FR-4.1, FR-4.5). */
    TemplateView find(TemplateQuery query);

    /** A page of the catalogue, without bodies (FR-4.1, FR-4.6). */
    List<TemplateSummary> list(TemplateListQuery query);

    /** Rendered text with its segment count and per-provider cost (FR-4.4). */
    TemplatePreviewView preview(TemplatePreviewQuery query);
}
