package uz.hamkorbank.commhub.adapter.in.admin.dto;

/**
 * Body of creating or updating a template card (§11.2 "Шаблоны", FR-4.1, §18.4).
 *
 * <p>The card, not its wording: versions have their own endpoint because they have their own lifecycle,
 * and editing a body is a different act from renaming its owner. {@code channel} is read on creation
 * only — a template that changed channel would be a different template with the same code in the
 * history of everything already sent from it.
 */
public record TemplateRequest(String channel, String direction, String owner) {}
