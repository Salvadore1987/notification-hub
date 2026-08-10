/**
 * Adapter layer: driving and driven adapters (AR-01, AR-04).
 *
 * <ul>
 *   <li>{@code in.rest} — source-system REST API v1
 *   <li>{@code in.kafka} — inbound consumers, split by traffic class
 *   <li>{@code in.admin} — admin panel REST BFF
 *   <li>{@code in.callback} — provider DLR webhooks
 *   <li>{@code out.persistence} — PostgreSQL (Spring Data) and the transactional outbox
 *   <li>{@code out.kafka} — status and DLQ producers
 *   <li>{@code out.provider} — playmobile, smsgate, smpp, smtp, apns, fcm
 *   <li>{@code out.notification} — alerting
 * </ul>
 *
 * <p>Flyway migrations live in {@code src/main/resources/db/migration} of this module (DB-01).
 */
package uz.hamkorbank.commhub.adapter;
