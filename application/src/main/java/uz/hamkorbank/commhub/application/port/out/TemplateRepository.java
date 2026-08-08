package uz.hamkorbank.commhub.application.port.out;

import java.util.Optional;
import uz.hamkorbank.commhub.domain.model.Template;
import uz.hamkorbank.commhub.domain.model.type.Channel;
import uz.hamkorbank.commhub.domain.model.vo.TemplateCode;
import uz.hamkorbank.commhub.domain.model.vo.TemplateId;

/**
 * Templates with their localised versions (§10.1 {@code template}, {@code template_version},
 * FR-4.1…FR-4.6).
 *
 * <p>The aggregate is returned with its versions attached, so that the use case can pick the
 * published one for the requested locale itself (FR-4.1).
 */
public interface TemplateRepository {

    Template save(Template template);

    Optional<Template> findById(TemplateId templateId);

    /** Template of a code; codes are unique across the catalogue (FR-4.1, FR-4.6). */
    Optional<Template> findByCode(TemplateCode code);

    /** Template of a code restricted to one channel, used by the admin panel listings (FR-4.1). */
    Optional<Template> findByCode(TemplateCode code, Channel channel);
}
