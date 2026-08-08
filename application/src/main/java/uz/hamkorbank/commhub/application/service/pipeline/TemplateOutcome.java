package uz.hamkorbank.commhub.application.service.pipeline;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.model.content.MessageContents;
import uz.hamkorbank.commhub.domain.model.type.RejectionReason;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Result of applying a template to a submission (FR-4.1, FR-4.3).
 *
 * @param contents content after substitution; unchanged when the submission carries no template
 * @param template resolved template, kept for the provider-side template binding (FR-4.5)
 * @param version published version the content was rendered from (FR-4.1)
 */
public record TemplateOutcome(
        MessageContents contents, Template template, TemplateVersion version, PipelineVerdict verdict) {

    public TemplateOutcome {
        Guard.notNull(verdict, "TemplateOutcome.verdict");
        Guard.isTrue(verdict.isRejected() || contents != null, "a passed TemplateOutcome must carry content");
    }

    /** The submission carries ready-made content; nothing to render (FR-1.2). */
    public static TemplateOutcome unchanged(MessageContents contents) {
        return new TemplateOutcome(contents, null, null, PipelineVerdict.passed());
    }

    /** The content was rendered from a published template version (FR-4.3). */
    public static TemplateOutcome rendered(MessageContents contents, Template template, TemplateVersion version) {
        return new TemplateOutcome(contents, template, version, PipelineVerdict.passed());
    }

    public static TemplateOutcome rejected(RejectionReason reason, String detail) {
        return new TemplateOutcome(null, null, null, PipelineVerdict.rejected(reason, detail));
    }

    public boolean isRejected() {
        return verdict.isRejected();
    }

    public Optional<Template> templateOptional() {
        return Optional.ofNullable(template);
    }

    public Optional<TemplateVersion> versionOptional() {
        return Optional.ofNullable(version);
    }
}
