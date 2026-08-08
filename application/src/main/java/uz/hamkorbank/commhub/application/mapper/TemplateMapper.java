package uz.hamkorbank.commhub.application.mapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import uz.hamkorbank.commhub.application.dto.TemplatePreviewView;
import uz.hamkorbank.commhub.application.dto.TemplateSummary;
import uz.hamkorbank.commhub.application.dto.TemplateVersionView;
import uz.hamkorbank.commhub.application.dto.TemplateView;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.TemplateVersion;
import uz.hamkorbank.commhub.domain.service.SmsSegmentation;

/**
 * Conversions of the template aggregate into the administration read models (FR-4.1…FR-4.5).
 *
 * <p>Default methods for the same reason as {@code ConfigMapper}: the aggregate exposes fluent accessors
 * and {@code Optional} state that the generator cannot introspect. The rule the mapper upholds is the one
 * that matters — no use case builds a view itself.
 */
@Mapper(componentModel = "spring")
public interface TemplateMapper {

    /** Versions ordered by locale and then by version number, which is how the panel lists them. */
    Comparator<TemplateVersion> VERSION_ORDER =
            Comparator.comparing(TemplateVersion::locale).thenComparingInt(TemplateVersion::version);

    default TemplateView toView(Template template) {
        return new TemplateView(
                template.id(),
                template.code(),
                template.channel(),
                template.direction().orElse(null),
                template.owner().orElse(null),
                template.catalogStatus(),
                template.versions().stream()
                        .sorted(VERSION_ORDER)
                        .map(this::toView)
                        .toList(),
                List.copyOf(template.providerMappings().values()));
    }

    default TemplateVersionView toView(TemplateVersion version) {
        return new TemplateVersionView(
                version.id(),
                version.version(),
                version.locale(),
                version.body(),
                version.status(),
                List.copyOf(version.declaredVariables()),
                new TemplateVersionView.Review(
                        version.createdBy(),
                        version.reviewedBy().orElse(null),
                        version.publishedAt().orElse(null)));
    }

    default TemplateSummary toSummary(Template template) {
        return new TemplateSummary(
                template.id(),
                template.code(),
                template.channel(),
                template.direction().orElse(null),
                template.owner().orElse(null),
                template.catalogStatus(),
                List.copyOf(template.publishedVersions().keySet()));
    }

    /**
     * Preview of one version (FR-4.4).
     *
     * @param costs cost per provider of the channel; the service resolves the tariffs
     * @param segmentation SMS segmentation; {@code null} for email and push
     */
    default TemplatePreviewView toPreview(
            Template template,
            TemplateVersion version,
            Map<String, String> variables,
            List<TemplatePreviewView.ProviderCost> costs,
            SmsSegmentation segmentation) {
        return new TemplatePreviewView(
                template.code(),
                template.channel(),
                version.locale(),
                new TemplatePreviewView.Version(version.version(), version.status()),
                version.renderPreview(variables),
                version.missingVariables(variables).stream().toList(),
                costs,
                segmentation);
    }
}
