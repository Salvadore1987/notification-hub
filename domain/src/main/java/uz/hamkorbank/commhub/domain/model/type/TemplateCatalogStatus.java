package uz.hamkorbank.commhub.domain.model.type;

/**
 * State of the catalogue card of a template (§10.1 {@code template.status}, FR-4.1).
 *
 * <p>Separate vocabulary from {@link TemplateStatus} on purpose: the card says whether the template is
 * still part of the catalogue an operator works with, while sendability is decided per version. A card
 * is archived instead of deleted — a template code appears in the history of every message rendered
 * from it, and a deleted row would make that history unreadable (FR-7.3).
 */
public enum TemplateCatalogStatus {
    ACTIVE,
    ARCHIVED;

    public boolean isArchived() {
        return this == ARCHIVED;
    }
}
