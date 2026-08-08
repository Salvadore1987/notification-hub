package uz.hamkorbank.commhub.application.port.out.provider;

import java.util.Map;
import uz.hamkorbank.commhub.domain.support.Guard;

/**
 * Binding onto a template registered on the provider side (FR-4.5, §9.1).
 *
 * <p>Playmobile accepts {@code template-id} + {@code variables} instead of a text; providers without
 * that capability (SMS Gate) receive the text already rendered by the Hub (SG-01), in which case no
 * binding is passed.
 *
 * @param approved whether the provider has approved the template; unapproved bindings are not used
 */
public record ProviderTemplateBinding(String providerTemplateId, Map<String, String> variables, boolean approved) {

    public ProviderTemplateBinding {
        Guard.notBlank(providerTemplateId, "ProviderTemplateBinding.providerTemplateId");
        variables = Guard.copyOf(variables);
    }
}
