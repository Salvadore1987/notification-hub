/**
 * Output DTOs of the application layer: use case results and the outbound status contract (§6.4).
 *
 * <p>Plain records without framework annotations. Transport adapters translate them into their own
 * representation — REST bodies, Kafka payloads — through their own mappers (AR-06).
 */
package uz.hamkorbank.commhub.application.dto;
