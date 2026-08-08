package uz.hamkorbank.commhub.domain.model.type;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Template version lifecycle (FR-4.1).
 *
 * <p>Only a {@link #PUBLISHED} version may be used for sending; publication requires a reviewer
 * different from the author (maker/checker, FR-4.2).
 */
public enum TemplateStatus {
    DRAFT,
    ON_REVIEW,
    PUBLISHED,
    ARCHIVED;

    private static final Map<TemplateStatus, Set<TemplateStatus>> TRANSITIONS;

    static {
        Map<TemplateStatus, Set<TemplateStatus>> transitions = new EnumMap<>(TemplateStatus.class);
        transitions.put(DRAFT, EnumSet.of(ON_REVIEW, ARCHIVED));
        transitions.put(ON_REVIEW, EnumSet.of(PUBLISHED, DRAFT, ARCHIVED));
        transitions.put(PUBLISHED, EnumSet.of(ARCHIVED));
        transitions.put(ARCHIVED, EnumSet.noneOf(TemplateStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    public boolean isSendable() {
        return this == PUBLISHED;
    }

    public boolean canTransitionTo(TemplateStatus next) {
        return next != null && TRANSITIONS.get(this).contains(next);
    }

    public Set<TemplateStatus> allowedTransitions() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }
}
