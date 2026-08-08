package uz.hamkorbank.commhub.application.port.in.query;

import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * "Show me this wording and what it costs" (FR-4.4).
 *
 * <p>{@code version} is optional and defaults to the latest version of the locale, published or not: the
 * preview exists to be used while writing, so it must reach a draft. Which version answered is reported
 * back in the view together with its status.
 *
 * @param variables values for the merge fields; the ones left out come back in
 *     {@code missingVariables} and stay visible in the text (FR-4.3)
 */
public record TemplatePreviewQuery(
        TemplateCode code, ContentLocale locale, Integer version, Map<String, String> variables) {

    public TemplatePreviewQuery {
        Guard.notNull(code, "TemplatePreviewQuery.code");
        Guard.notNull(locale, "TemplatePreviewQuery.locale");
        variables = Guard.copyOf(variables);
        if (version != null) {
            Guard.positive(version, "TemplatePreviewQuery.version");
        }
    }

    public Optional<Integer> versionOptional() {
        return Optional.ofNullable(version);
    }
}
