package uz.hamkorbank.commhub.domain.model.type;

/** Load-balancing strategy between the active providers of one channel (FR-2.3). */
public enum BalancingStrategy {

    /** Even rotation over the selectable providers. */
    ROUND_ROBIN,
    /** Rotation proportional to the provider weight. */
    WEIGHTED,
    /** Cheapest provider for the actual segment count (least-cost routing). */
    LEAST_COST,
    /** No balancing: always the first selectable provider of the fallback order. */
    PRIMARY_ONLY
}
