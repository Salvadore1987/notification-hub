package uz.hamkorbank.commhub.application.port.in.query;

import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/** One template of the catalogue by its business code (FR-4.1). */
public record TemplateQuery(TemplateCode code) {

    public TemplateQuery {
        Guard.notNull(code, "TemplateQuery.code");
    }
}
