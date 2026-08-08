/**
 * Use case implementations: orchestration only (AR-06, project rule).
 *
 * <p>A service loads aggregates, walks them through the pipeline stages, applies the status
 * transitions of the domain, saves and appends the outbox event — inside one transaction (AD-03). No
 * mapping (that is {@code mapper/}), no business rules that belong to an aggregate (that is
 * {@code domain/}), no transport concerns (that is {@code adapter/in}).
 */
package uz.hamkorbank.commhub.application.service;
