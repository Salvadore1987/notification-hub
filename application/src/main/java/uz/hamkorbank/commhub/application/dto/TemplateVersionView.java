package uz.hamkorbank.commhub.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.type.ContentLocale;
import uz.hamkorbank.commhub.domain.model.type.TemplateStatus;
import uz.hamkorbank.commhub.domain.model.vo.TemplateVersionId;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * One localised version of a template as the administration screens see it (FR-4.1…FR-4.3).
 *
 * @param variables merge fields the body declares, in the order they appear in it (FR-4.3)
 */
public record TemplateVersionView(
        TemplateVersionId versionId,
        int version,
        ContentLocale locale,
        String subject,
        String text,
        TemplateStatus status,
        List<String> variables,
        Review review) {

    public TemplateVersionView {
        Guard.notNull(versionId, "TemplateVersionView.versionId");
        Guard.positive(version, "TemplateVersionView.version");
        Guard.notNull(locale, "TemplateVersionView.locale");
        Guard.notBlank(text, "TemplateVersionView.text");
        Guard.notNull(status, "TemplateVersionView.status");
        variables = Guard.copyOf(variables);
        Guard.notNull(review, "TemplateVersionView.review");
    }

    public Optional<String> subjectOptional() {
        return Optional.ofNullable(subject);
    }

    /**
     * Who wrote the version and who let it out (FR-4.2, FR-7.3).
     *
     * <p>Both names are shown because that pair <em>is</em> the maker/checker evidence: a published
     * version whose reviewer equals its author would be the violation, and it has to be visible.
     */
    public record Review(String createdBy, String reviewedBy, Instant publishedAt) {

        public Review {
            Guard.notBlank(createdBy, "Review.createdBy");
        }

        public Optional<String> reviewedByOptional() {
            return Optional.ofNullable(reviewedBy);
        }

        public Optional<Instant> publishedAtOptional() {
            return Optional.ofNullable(publishedAt);
        }
    }
}
