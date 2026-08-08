/**
 * Stages of the single, channel-agnostic pipeline shared by every use case (SRS §5.1):
 * validation → deduplication → templating → delivery filters → quotas → routing.
 *
 * <p>Each stage is a small collaborator of the use case services; they hold no state, take instants
 * as parameters and return verdict records instead of throwing, so that a rejection stays an ordinary
 * outcome of the pipeline with a canonical reason (IR-01).
 */
package uz.hamkorbank.commhub.application.service.pipeline;
