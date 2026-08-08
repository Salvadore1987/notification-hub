/**
 * Input ports (driving side of the hexagon): one interface per use case (AR-06, SRS §4.1).
 *
 * <p>Every port takes an explicit {@code Command} record from
 * {@link uz.hamkorbank.commhub.application.port.in.command} and returns a result record from
 * {@link uz.hamkorbank.commhub.application.dto}. REST and Kafka adapters only translate their
 * transport DTOs into commands — no business logic lives in an adapter (AR-06).
 */
package uz.hamkorbank.commhub.application.port.in;
