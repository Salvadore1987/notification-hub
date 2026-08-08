package uz.hamkorbank.commhub.domain.model;

import java.util.Map;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Reference from a message to a template plus the merge variables supplied by the source system
 * (SRS §5.2, FR-1.2, FR-4.3).
 *
 * @param locale requested locale; {@code null} means "take the stream default"
 * @param variables merge values, e.g. {@code {"NAME": "IVAN"}}
 */
public record TemplateRef(TemplateCode code, ContentLocale locale, Map<String, String> variables) {

    public TemplateRef {
        Guard.notNull(code, "TemplateRef.code");
        variables = Guard.copyOf(variables);
    }

    public static TemplateRef of(TemplateCode code) {
        return new TemplateRef(code, null, Map.of());
    }

    public static TemplateRef of(TemplateCode code, ContentLocale locale) {
        return new TemplateRef(code, locale, Map.of());
    }

    public static TemplateRef of(TemplateCode code, ContentLocale locale, Map<String, String> variables) {
        return new TemplateRef(code, locale, variables);
    }

    public Optional<ContentLocale> localeOptional() {
        return Optional.ofNullable(locale);
    }

    public Optional<String> variable(String name) {
        return Optional.ofNullable(variables.get(name));
    }

    public TemplateRef withVariables(Map<String, String> mergeVariables) {
        return new TemplateRef(code, locale, mergeVariables);
    }
}
