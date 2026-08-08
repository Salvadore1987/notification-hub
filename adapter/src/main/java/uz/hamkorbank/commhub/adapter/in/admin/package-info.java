/**
 * The admin REST BFF: one controller per section of §11.2 (UI-02, UI-03, SEC-02, SEC-03).
 *
 * <p>A driving adapter and nothing more. Every endpoint here translates a request into a command or a
 * query, calls a use case and renders the answer (AR-06); the decisions stay in the application layer,
 * which is why the dry run of §11.2 "Маршрутизация" runs the real router rather than a second copy of
 * the rules, and why the batch card answers exactly the body of §8.2 rather than a screen-shaped variant
 * of it.
 *
 * <p>Three things belong to this layer and are decided here rather than below it.
 *
 * <p><b>Who may call what.</b> The roles of §11.2 are {@code @PreAuthorize} expressions in
 * {@code support/AdminAuthority}, written once per section. The application layer has no notion of a
 * role, and it should not: what an endpoint refuses is a property of the API, not of the use case.
 *
 * <p><b>How much of an address a role sees.</b> §11.2 gives the message screens to {@code OPERATOR+} and
 * to {@code VIEWER} with masked addresses, so the masking happens on the way out, in
 * {@code support/AdminMasking}, and never in the query — which has to compare full addresses to find
 * anything at all (DB-04, DB-05, SEC-06).
 *
 * <p><b>What a period defaults to.</b> {@code message} is partitioned by {@code accepted_at} (DB-02), so
 * a screen opened without a period would scan every partition retention has kept — and screens get
 * opened without one exactly when somebody is in a hurry. {@code support/AdminPeriod} fills in the last
 * day and refuses a window no single request should run.
 *
 * <p>Errors are the existing ones. A malformed field raises {@code InboundContractException} and comes
 * back as the {@code problem+json} of IR-01 through the advices in {@code rest/handlers}; a conflicting
 * configuration edit is {@code ConfigurationConflictException} (409) and a missing entity is
 * {@code NotFoundException} (404), both of which the same advices already render. There is no separate
 * error vocabulary for the panel, because an operator and a source system hitting the same wall should
 * be told the same thing.
 */
package uz.hamkorbank.commhub.adapter.in.admin;
