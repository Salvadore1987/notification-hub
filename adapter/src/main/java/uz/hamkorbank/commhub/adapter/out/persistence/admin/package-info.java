/**
 * Persistence of the two things the administration screen owns and nothing else does: the global kill
 * switch (FR-3.2) and the operator-editable system parameters (§11.2 "Администрирование", NF-06).
 *
 * <p>Its own package rather than a corner of {@code config}, because these are not routing
 * configuration. Routing configuration is an aggregate with rules that the domain enforces and that the
 * administration use cases load in order to mutate; these two are a switch and a key-value table, read
 * on completely different paths — the switch on every message, the parameters on one screen — and their
 * caching decisions follow from that difference rather than from what they are stored in.
 */
package uz.hamkorbank.commhub.adapter.out.persistence.admin;
