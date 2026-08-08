package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uz.hamkorbank.commhub.domain.exception.DomainValidationException;
import uz.hamkorbank.commhub.domain.exception.InvalidStatusTransitionException;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One localised version of a template (§10.1 {@code template_version}, FR-4.1…FR-4.3).
 *
 * <p>Merge fields are written as {@code {NAME}} and substituted from the variables supplied by the
 * source system. In strict mode a missing variable is a validation error (FR-4.3).
 *
 * <p>Publication enforces maker/checker: the reviewer must differ from the author (FR-4.2).
 */
public final class TemplateVersion extends AggregateRoot<TemplateVersionId> {

    /** Merge-field syntax of the Hub: {@code {UPPER_SNAKE}} (FR-4.3). */
    public static final Pattern MERGE_FIELD_PATTERN = Pattern.compile("\\{([A-Z0-9_]{1,64})}");

    public static final int MAX_AUTHOR_LENGTH = 128;

    private final TemplateId templateId;
    private final int version;
    private final ContentLocale locale;
    private final Body body;
    private final String createdBy;

    private TemplateStatus status;
    private String reviewedBy;
    private Instant publishedAt;

    private TemplateVersion(
            TemplateVersionId id,
            TemplateId templateId,
            int version,
            ContentLocale locale,
            Body body,
            String createdBy) {
        super(id);
        this.templateId = Guard.notNull(templateId, "TemplateVersion.templateId");
        this.version = Guard.positive(version, "TemplateVersion.version");
        this.locale = Guard.notNull(locale, "TemplateVersion.locale");
        this.body = Guard.notNull(body, "TemplateVersion.body");
        this.createdBy = Guard.maxLength(
                Guard.notBlank(createdBy, "TemplateVersion.createdBy"), MAX_AUTHOR_LENGTH, "TemplateVersion.createdBy");
        this.status = TemplateStatus.DRAFT;
    }

    /** Creates a new draft version (FR-4.1). */
    public static TemplateVersion draft(
            TemplateVersionId id, TemplateId templateId, int version, ContentLocale locale, Body body, String author) {
        return new TemplateVersion(id, templateId, version, locale, body, author);
    }

    public void submitForReview() {
        transitionTo(TemplateStatus.ON_REVIEW);
    }

    public void returnToDraft() {
        transitionTo(TemplateStatus.DRAFT);
    }

    /** Publishes the version; the reviewer must not be its author (FR-4.2). */
    public void publish(String reviewer, Instant publishedAt) {
        Guard.notBlank(reviewer, "reviewer");
        Guard.notNull(publishedAt, "publishedAt");
        Guard.isTrue(
                !reviewer.equalsIgnoreCase(createdBy),
                "maker/checker: the author of a template version may not publish it (FR-4.2)");
        transitionTo(TemplateStatus.PUBLISHED);
        this.reviewedBy = reviewer;
        this.publishedAt = publishedAt;
    }

    public void archive() {
        transitionTo(TemplateStatus.ARCHIVED);
    }

    /** Merge fields declared by the template body (FR-4.3). */
    public Set<String> declaredVariables() {
        return body.declaredVariables();
    }

    /**
     * Renders the body with the supplied variables (FR-4.3).
     *
     * @param strict when {@code true}, a missing variable raises {@link DomainValidationException}
     */
    public Rendered render(Map<String, String> variables, boolean strict) {
        Guard.isTrue(status.isSendable(), "only a PUBLISHED template version may be rendered (FR-4.1)");
        Map<String, String> values = Guard.copyOf(variables);
        String subject = body.subject() == null ? null : substitute(body.subject(), values, strict);
        return new Rendered(subject, substitute(body.text(), values, strict));
    }

    public boolean isSendable() {
        return status.isSendable();
    }

    public TemplateId templateId() {
        return templateId;
    }

    public int version() {
        return version;
    }

    public ContentLocale locale() {
        return locale;
    }

    public Body body() {
        return body;
    }

    public String createdBy() {
        return createdBy;
    }

    public TemplateStatus status() {
        return status;
    }

    public Optional<String> reviewedBy() {
        return Optional.ofNullable(reviewedBy);
    }

    public Optional<Instant> publishedAt() {
        return Optional.ofNullable(publishedAt);
    }

    private void transitionTo(TemplateStatus next) {
        if (!status.canTransitionTo(next)) {
            throw InvalidStatusTransitionException.of("TemplateVersion", status, next);
        }
        this.status = next;
    }

    private static String substitute(String source, Map<String, String> values, boolean strict) {
        Matcher matcher = MERGE_FIELD_PATTERN.matcher(source);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                if (strict) {
                    throw new DomainValidationException("missing value for merge field {%s}".formatted(name));
                }
                value = "";
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /**
     * Body of a template version (§10.1 {@code template_version}).
     *
     * @param subject email subject; {@code null} for SMS and push titles carried in the text
     * @param text SMS text, push body or email body, with {@code {MERGE_FIELDS}}
     */
    public record Body(String subject, String text) {

        public static final int MAX_TEXT_LENGTH = 8192;

        public Body {
            Guard.notBlank(text, "Body.text");
            Guard.maxLength(text, MAX_TEXT_LENGTH, "Body.text");
        }

        public static Body ofText(String text) {
            return new Body(null, text);
        }

        public static Body of(String subject, String text) {
            return new Body(subject, text);
        }

        /** Merge fields referenced by the subject and the text (FR-4.3). */
        public Set<String> declaredVariables() {
            Set<String> variables = new LinkedHashSet<>();
            collectInto(subject, variables);
            collectInto(text, variables);
            return Set.copyOf(variables);
        }

        private static void collectInto(String source, Set<String> target) {
            if (source == null) {
                return;
            }
            Matcher matcher = MERGE_FIELD_PATTERN.matcher(source);
            while (matcher.find()) {
                target.add(matcher.group(1));
            }
        }
    }

    /**
     * Result of rendering a template version (FR-4.3).
     *
     * @param subject rendered subject; {@code null} when the version has none
     */
    public record Rendered(String subject, String text) {

        public Rendered {
            Guard.notBlank(text, "Rendered.text");
        }

        public Optional<String> subjectOptional() {
            return Optional.ofNullable(subject);
        }
    }
}
