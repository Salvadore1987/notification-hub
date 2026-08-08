package uz.hamkorbank.commhub.domain.model;

import java.time.Instant;
import java.util.Collections;
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
    private final String createdBy;

    private Body body;
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

    /**
     * Rewrites the text of a draft (FR-4.1).
     *
     * <p>Only a {@code DRAFT} may be edited: a version under review is what a reviewer is looking at, and
     * a published one is what messages were rendered from — changing either in place would make the audit
     * trail of a sent message point at a text that never went out (FR-7.3). Later texts are new versions.
     */
    public void updateBody(Body newBody) {
        Guard.isTrue(status == TemplateStatus.DRAFT, "only a DRAFT version may be edited (FR-4.1)");
        this.body = Guard.notNull(newBody, "body");
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

    /**
     * Renders the version for the administration preview (FR-4.4).
     *
     * <p>Two departures from {@link #render(Map, boolean)}, both because a preview is not a send: the
     * status is not checked — the point of a preview is to look at a draft before it is published — and a
     * missing variable leaves the merge field visible as {@code {NAME}} instead of failing, so that the
     * operator sees which values the source system will have to supply. What is missing is reported by
     * {@link #missingVariables(Map)}, not hidden in an empty string.
     */
    public Rendered renderPreview(Map<String, String> variables) {
        Map<String, String> values = Guard.copyOf(variables);
        String subject = body.subject() == null ? null : preview(body.subject(), values);
        return new Rendered(subject, preview(body.text(), values));
    }

    /** Merge fields of the body the supplied variables have no value for (FR-4.3, FR-4.4). */
    public Set<String> missingVariables(Map<String, String> variables) {
        Map<String, String> values = Guard.copyOf(variables);
        Set<String> missing = new LinkedHashSet<>();
        for (String name : declaredVariables()) {
            if (values.get(name) == null) {
                missing.add(name);
            }
        }
        return Collections.unmodifiableSet(missing);
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

    /** Substitution of the preview: an absent value stays visible as its merge field (FR-4.4). */
    private static String preview(String source, Map<String, String> values) {
        Matcher matcher = MERGE_FIELD_PATTERN.matcher(source);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
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

        /**
         * Merge fields referenced by the subject and the text, in the order they appear (FR-4.3).
         *
         * <p>Declaration order is kept deliberately: the admin panel lists the fields an operator has to
         * fill in, and reading them in the order of the text is how the text is proof-read (FR-4.4).
         */
        public Set<String> declaredVariables() {
            Set<String> variables = new LinkedHashSet<>();
            collectInto(subject, variables);
            collectInto(text, variables);
            return Collections.unmodifiableSet(variables);
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
