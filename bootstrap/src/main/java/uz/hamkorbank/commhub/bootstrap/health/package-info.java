/**
 * Health of the Hub as the platform sees it (NF-05).
 *
 * <p>Three probes, three different questions, and the difference between them is the whole point:
 *
 * <ul>
 *   <li><strong>liveness</strong> — is this JVM still working? Only the application's own liveness state
 *       answers it. Nothing external belongs here: restarting a pod because PostgreSQL is unreachable
 *       replaces one outage with a crash loop during it;
 *   <li><strong>readiness</strong> — may this instance receive requests? The application's readiness
 *       state plus the database, because a submission that cannot be committed cannot be accepted
 *       (AD-03). The broker and the providers are deliberately outside: the outbox exists so that a
 *       broker outage does not stop ingest, and a provider outage is the same on every pod;
 *   <li><strong>startup</strong> — may the probes start counting? The database again, since Flyway has to
 *       have finished before this instance is anything at all (DB-01).
 * </ul>
 *
 * <p>The indicators that are not in a group are still published under {@code /actuator/health} and are
 * what the alerts of OBS-04 read. "Visible" and "decides whether traffic reaches this pod" are two
 * different roles, and conflating them is how an alertable condition turns into an outage.
 *
 * <p>They live in {@code bootstrap} rather than beside the adapters they observe because a health
 * contributor is deployment machinery: it exists for Kubernetes, it speaks the actuator's vocabulary, and
 * the adapter module has no reason to compile against it (AR-01).
 */
package uz.hamkorbank.commhub.bootstrap.health;
