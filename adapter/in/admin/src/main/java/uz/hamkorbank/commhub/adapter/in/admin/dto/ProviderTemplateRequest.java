package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of recording the template as it is registered at a provider (§11.2 "Шаблоны", FR-4.5).
 *
 * @param approved whether the provider has approved the wording; an unapproved mapping is recorded so
 *     the panel can show what is still waiting on the operator, which is the state this screen exists
 *     to make visible
 */
public record ProviderTemplateRequest(String providerTemplateId, Boolean approved) {

    public ProviderTemplateRequest {
        approved = approved != null && approved;
    }
}
